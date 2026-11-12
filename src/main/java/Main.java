import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.JsonValue;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.ChatCompletionMessageParam;
import com.openai.models.chat.completions.ChatCompletionSystemMessageParam;
import com.openai.models.chat.completions.ChatCompletionToolMessageParam;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.List;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.LinkedHashMap;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.util.Comparator;
import java.util.stream.Stream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Main {
    private record SkillDocument(String body, Map<String, String> metadata) {}
    private record DiagnosticEntry(String uri, String key, String relativePath, int line, int character, Integer severity, String message) {}
    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

    public static void main(String[] args) {
        if (args.length < 2 || !"-p".equals(args[0])) {
            System.err.println("Usage: program -p <prompt>");
            System.exit(1);
        }

        String prompt = args[1];

        String apiKey = System.getenv("OPENROUTER_API_KEY");
        String baseUrl = System.getenv("OPENROUTER_BASE_URL");
        if (baseUrl == null || baseUrl.isEmpty()) {
            baseUrl = "https://openrouter.ai/api/v1";
        }

        if (apiKey == null || apiKey.isEmpty()) {
            throw new RuntimeException("OPENROUTER_API_KEY is not set");
        }

        LSPClient lspClient;
        try {
            lspClient = LSPClient.fromEnvironment();
        } catch (Exception e) {
            throw new RuntimeException("failed to initialize LSP client", e);
        }
        if (lspClient != null) {
            System.err.println("LSP client connected");
        }

        OpenAIClient client = OpenAIOkHttpClient.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .build();

        List<ChatCompletionMessageParam> messages = new ArrayList<>();
        Map<String, Integer> lspDocumentVersions = new HashMap<>();
        DiagnosticTracker diagnosticTracker = new DiagnosticTracker(lspClient, lspDocumentVersions);
        String skillsPrompt = loadSkillsPrompt();
        if (skillsPrompt != null) {
            messages.add(ChatCompletionMessageParam.ofSystem(
                    ChatCompletionSystemMessageParam.builder()
                            .content(skillsPrompt)
                            .build()));
        }
        if (lspClient != null) {
            addSystemMessage(messages, "Use the LSP tool to find definitions and references. Treat <new-diagnostics> reports returned by tools as feedback and use them to repair affected code.");
        }
        addUserMessages(client, lspClient, lspDocumentVersions, diagnosticTracker, messages, prompt);
        System.out.print(runAgentLoop(client, lspClient, lspDocumentVersions, diagnosticTracker, messages));
    }

    private static String runAgentLoop(OpenAIClient client, LSPClient lspClient, Map<String, Integer> lspDocumentVersions, DiagnosticTracker diagnosticTracker, List<ChatCompletionMessageParam> messages) {
        LatestToolResult latestToolResult = new LatestToolResult();
        while (true) {
            try {
                diagnosticTracker.poll();
                if (latestToolResult.canAppendDiagnostics() && diagnosticTracker.hasNewDiagnostics()) {
                    latestToolResult.appendDiagnostics(messages, diagnosticTracker.takeNewDiagnostics());
                }
            } catch (Exception e) {
                throw new RuntimeException("failed to read LSP diagnostics", e);
            }

            ChatCompletion response = client.chat().completions().create(
                    ChatCompletionCreateParams.builder()
                            .model("anthropic/claude-haiku-4.5")
                            .messages(messages)
                            .putAdditionalBodyProperty(
                                    "tools",
                                    JsonValue.from(toolDefinitions(lspClient)))
                            .build());
            if (response.choices().isEmpty()) {
                throw new RuntimeException("no choices in response");
            }

            var message = response.choices().get(0).message();
            messages.add(ChatCompletionMessageParam.ofAssistant(message.toParam()));

            var toolCalls = message.toolCalls();
            if (toolCalls.isEmpty() || toolCalls.get().isEmpty()) {
                try {
                    diagnosticTracker.poll();
                    String diagnostics = diagnosticTracker.hasNewDiagnostics()
                            ? diagnosticTracker.takeNewDiagnostics()
                            : "";
                    if (!diagnostics.isEmpty()) {
                        addUserMessage(messages, diagnostics);
                        continue;
                    }
                } catch (Exception e) {
                    throw new RuntimeException("failed to read LSP diagnostics", e);
                }
                return message.content().orElse("");
            }

            for (var toolCall : toolCalls.get()) {
                if (!toolCall.isFunction()) {
                    throw new RuntimeException("unsupported tool call type");
                }

                var functionCall = toolCall.asFunction();
                var function = functionCall.function();
                String result;
                boolean isWrite = "Write".equals(function.name());
                if ("Read".equals(function.name())) {
                    ReadFileTool arguments = function.arguments(ReadFileTool.class);
                    try {
                        result = Files.readString(Path.of(arguments.filePath));
                    } catch (java.io.IOException e) {
                        throw new RuntimeException("failed to read file: " + arguments.filePath, e);
                    }
                } else if ("Write".equals(function.name())) {
                    WriteFileTool arguments = function.arguments(WriteFileTool.class);
                    Path filePath = Path.of(arguments.filePath);
                    try {
                        Path parent = filePath.getParent();
                        if (parent != null) {
                            Files.createDirectories(parent);
                        }
                        Files.writeString(filePath, arguments.content);
                        if (lspClient != null) {
                            syncWrittenDocument(lspClient, lspDocumentVersions, filePath);
                        }
                        result = "File written successfully.";
                    } catch (Exception e) {
                        throw new RuntimeException("failed to write file: " + arguments.filePath, e);
                    }
                } else if ("Bash".equals(function.name())) {
                    BashTool arguments = function.arguments(BashTool.class);
                    result = executeBash(arguments.command);
                } else if ("Skill".equals(function.name())) {
                    SkillTool arguments = function.arguments(SkillTool.class);
                    result = invokeSkill(client, lspClient, lspDocumentVersions, diagnosticTracker, arguments);
                } else if ("LSP".equals(function.name())) {
                    LSPTool arguments = function.arguments(LSPTool.class);
                    try {
                        result = executeLspTool(lspClient, lspDocumentVersions, arguments);
                    } catch (Exception e) {
                        throw new RuntimeException("LSP definition lookup failed", e);
                    }
                } else {
                    throw new RuntimeException("unsupported tool: " + function.name());
                }

                int toolMessageIndex = messages.size();
                messages.add(ChatCompletionMessageParam.ofTool(
                        ChatCompletionToolMessageParam.builder()
                                .toolCallId(functionCall.id())
                                .content(result)
                                .build()));
                latestToolResult.record(toolMessageIndex, functionCall.id(), result, isWrite);
            }
        }
    }

    private static final class LatestToolResult {
        private int messageIndex = -1;
        private String toolCallId;
        private String content;
        private boolean writeResult;

        private void record(int messageIndex, String toolCallId, String content, boolean writeResult) {
            this.messageIndex = messageIndex;
            this.toolCallId = toolCallId;
            this.content = content;
            this.writeResult = writeResult;
        }

        private boolean canAppendDiagnostics() {
            return messageIndex >= 0 && !writeResult;
        }

        private void appendDiagnostics(List<ChatCompletionMessageParam> messages, String diagnostics) {
            content += "\n\n" + diagnostics;
            ChatCompletionToolMessageParam updatedToolMessage = ChatCompletionToolMessageParam.builder()
                    .toolCallId(toolCallId)
                    .content(content)
                    .build();
            messages.set(messageIndex, ChatCompletionMessageParam.ofTool(updatedToolMessage));
        }
    }

    private static List<Map<String, Object>> toolDefinitions(LSPClient lspClient) {
        List<Map<String, Object>> tools = new ArrayList<>();
        tools.add(Map.of(
                "type", "function",
                "function", Map.of(
                        "name", "Read",
                        "description", "Read and return the contents of a file",
                        "parameters", Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "file_path", Map.of(
                                                "type", "string",
                                                "description", "The path to the file to read")),
                                "required", List.of("file_path")))));
        tools.add(Map.of(
                "type", "function",
                "function", Map.of(
                        "name", "Write",
                        "description", "Write content to a file",
                        "parameters", Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "file_path", Map.of(
                                                "type", "string",
                                                "description", "The path of the file to write to"),
                                        "content", Map.of(
                                                "type", "string",
                                                "description", "The content to write to the file")),
                                "required", List.of("file_path", "content")))));
        tools.add(Map.of(
                "type", "function",
                "function", Map.of(
                        "name", "Bash",
                        "description", "Execute a shell command",
                        "parameters", Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "command", Map.of(
                                                "type", "string",
                                                "description", "The command to execute")),
                                "required", List.of("command")))));
        tools.add(Map.of(
                "type", "function",
                "function", Map.of(
                        "name", "Skill",
                        "description", "Load a skill's instructions into the conversation",
                        "parameters", Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "name", Map.of(
                                                "type", "string",
                                                "description", "The name of the skill to use"),
                                        "args", Map.of(
                                                "type", "string",
                                                "description", "Optional arguments for the skill")),
                                "required", List.of("name")))));

        if (lspClient != null) {
            tools.add(Map.of(
                    "type", "function",
                    "function", Map.of(
                            "name", "LSP",
                            "description", "Finds a symbol's definition or references. Line and character inputs are 1-based.",
                            "parameters", Map.of(
                                    "type", "object",
                                    "properties", Map.of(
                                            "operation", Map.of(
                                                    "type", "string",
                                                    "enum", List.of("goToDefinition", "findReferences")),
                                            "filePath", Map.of(
                                                    "type", "string",
                                                    "description", "Absolute path, or a path relative to the agent's current working directory."),
                                            "line", Map.of(
                                                    "type", "integer",
                                                    "minimum", 1,
                                                    "description", "1-based line number of the symbol."),
                                            "character", Map.of(
                                                    "type", "integer",
                                                    "minimum", 1,
                                                    "description", "1-based character offset in UTF-16 code units.")),
                                    "required", List.of("operation", "filePath", "line", "character")))));
        }
        return tools;
    }

    private static String executeLspTool(LSPClient lspClient, Map<String, Integer> documentVersions, LSPTool arguments) throws Exception {
        if (lspClient == null) {
            return "LSP is not configured";
        }
        if (!"goToDefinition".equals(arguments.operation) && !"findReferences".equals(arguments.operation)) {
            return "Unsupported LSP operation: " + arguments.operation;
        }

        Path sourcePath = Path.of(arguments.filePath).toAbsolutePath().normalize();
        String sourceUri = documentUri(sourcePath);
        ensureDocumentOpen(lspClient, documentVersions, sourcePath);

        Map<String, Object> textDocument = Map.of("uri", sourceUri);
        Map<String, Object> position = Map.of(
                "line", arguments.line - 1,
                "character", arguments.character - 1);
        Map<String, Object> params = "findReferences".equals(arguments.operation)
                ? Map.of(
                        "textDocument", textDocument,
                        "position", position,
                        "context", Map.of("includeDeclaration", true))
                : Map.of("textDocument", textDocument, "position", position);
        Map<String, Object> request = Map.of(
                "method", "goToDefinition".equals(arguments.operation)
                        ? "textDocument/definition"
                        : "textDocument/references",
                "params", params);
        String response = lspClient.call("request", JSON_MAPPER.writeValueAsString(request));
        JsonNode result = JSON_MAPPER.readTree(response);
        if ("goToDefinition".equals(arguments.operation)) {
            return formatDefinitionResult(result);
        }
        return formatReferencesResult(result);
    }

    private static void ensureDocumentOpen(LSPClient lspClient, Map<String, Integer> documentVersions, Path filePath) throws Exception {
        Path absolutePath = filePath.toAbsolutePath().normalize();
        String uri = documentUri(absolutePath);
        if (documentVersions.containsKey(uri)) {
            return;
        }

        String languageId = languageForPath(lspClient, absolutePath);
        if (languageId == null) {
            throw new IllegalArgumentException("no language configured for file: " + absolutePath);
        }
        String text = Files.readString(absolutePath, StandardCharsets.UTF_8);
        Map<String, Object> didOpen = Map.of(
                "method", "textDocument/didOpen",
                "params", Map.of(
                        "textDocument", Map.of(
                                "uri", uri,
                                "languageId", languageId,
                                "version", 1,
                                "text", text)));
        lspClient.call("notify", JSON_MAPPER.writeValueAsString(didOpen));
        documentVersions.put(uri, 1);
    }

    private static void syncWrittenDocument(LSPClient lspClient, Map<String, Integer> documentVersions, Path filePath) throws Exception {
        Path absolutePath = filePath.toAbsolutePath().normalize();
        String languageId = languageForPath(lspClient, absolutePath);
        if (languageId == null) {
            return;
        }

        String uri = documentUri(absolutePath);
        String savedText = Files.readString(absolutePath, StandardCharsets.UTF_8);
        Integer previousVersion = documentVersions.get(uri);
        Map<String, Object> notification;
        int nextVersion;
        if (previousVersion == null) {
            nextVersion = 1;
            notification = Map.of(
                    "method", "textDocument/didOpen",
                    "params", Map.of(
                            "textDocument", Map.of(
                                    "uri", uri,
                                    "languageId", languageId,
                                    "version", nextVersion,
                                    "text", savedText)));
        } else {
            nextVersion = previousVersion + 1;
            notification = Map.of(
                    "method", "textDocument/didChange",
                    "params", Map.of(
                            "textDocument", Map.of(
                                    "uri", uri,
                                    "version", nextVersion),
                            "contentChanges", List.of(Map.of("text", savedText))));
        }

        lspClient.call("notify", JSON_MAPPER.writeValueAsString(notification));
        documentVersions.put(uri, nextVersion);
    }

    private static String languageForPath(LSPClient lspClient, Path filePath) {
        Path fileNamePath = filePath.getFileName();
        if (fileNamePath == null) {
            return null;
        }
        String fileName = fileNamePath.toString();
        int extensionStart = fileName.lastIndexOf('.');
        String extension = extensionStart >= 0 ? fileName.substring(extensionStart) : "";
        return lspClient.extensionToLanguage.get(extension);
    }

    private static String documentUri(Path filePath) {
        return filePath.toAbsolutePath().normalize().toUri().toASCIIString();
    }

    private static String formatDefinitionResult(JsonNode location) {
        String relativePath = relativePathFromUri(location.get("uri").asText());
        JsonNode start = location.path("range").path("start");
        return "Defined in " + relativePath + ":" + (start.get("line").asInt() + 1)
                + ":" + (start.get("character").asInt() + 1);
    }

    private static String formatReferencesResult(JsonNode locations) {
        if (!locations.isArray() || locations.size() == 0) {
            return "No references found.";
        }

        Map<String, List<String>> referencesByFile = new LinkedHashMap<>();
        for (JsonNode location : locations) {
            String relativePath = relativePathFromUri(location.path("uri").asText());
            JsonNode start = location.path("range").path("start");
            String position = "  Line " + (start.get("line").asInt() + 1)
                    + ":" + (start.get("character").asInt() + 1);
            referencesByFile.computeIfAbsent(relativePath, ignored -> new ArrayList<>()).add(position);
        }

        int referenceCount = locations.size();
        int fileCount = referencesByFile.size();
        StringBuilder formatted = new StringBuilder()
                .append("Found ").append(referenceCount).append(referenceCount == 1 ? " reference" : " references")
                .append(" across ").append(fileCount).append(fileCount == 1 ? " file:\n\n" : " files:\n\n");
        referencesByFile.forEach((file, positions) -> {
            formatted.append(file).append(':').append('\n');
            positions.forEach(position -> formatted.append(position).append('\n'));
            formatted.append('\n');
        });
        return formatted.toString().stripTrailing();
    }

    private static String relativePathFromUri(String uri) {
        Path targetPath = Path.of(URI.create(uri)).toAbsolutePath().normalize();
        return Path.of("").toAbsolutePath().normalize()
                .relativize(targetPath)
                .toString()
                .replace('\\', '/');
    }

    private static final class DiagnosticTracker {
        private final LSPClient lspClient;
        private final Map<String, Integer> documentVersions;
        private final Map<String, Integer> lastReportVersions = new HashMap<>();
        private final Map<String, List<DiagnosticEntry>> latestReports = new HashMap<>();
        private final Map<String, Set<String>> shownKeys = new HashMap<>();
        private final Map<String, List<DiagnosticEntry>> pending = new LinkedHashMap<>();
        private long cursor;

        private DiagnosticTracker(LSPClient lspClient, Map<String, Integer> documentVersions) {
            this.lspClient = lspClient;
            this.documentVersions = documentVersions;
        }

        private void poll() throws Exception {
            if (lspClient == null) {
                return;
            }

            String payload = JSON_MAPPER.writeValueAsString(Map.of("after", cursor, "timeoutMs", 0));
            JsonNode batch = JSON_MAPPER.readTree(lspClient.call("notifications", payload));
            if (batch.has("cursor")) {
                cursor = batch.path("cursor").asLong(cursor);
            }

            JsonNode notifications = batch.path("notifications");
            if (!notifications.isArray()) {
                return;
            }
            for (JsonNode notification : notifications) {
                if ("textDocument/publishDiagnostics".equals(notification.path("method").asText())) {
                    acceptReport(notification.path("params"));
                }
            }
        }

        private void acceptReport(JsonNode params) {
            String uri = URI.create(params.path("uri").asText()).normalize().toASCIIString();
            Integer reportVersion = params.hasNonNull("version") ? params.path("version").asInt() : null;
            Integer documentVersion = documentVersions.get(uri);
            Integer previousReportVersion = lastReportVersions.get(uri);
            if (reportVersion != null) {
                if (documentVersion != null && reportVersion < documentVersion) {
                    return;
                }
                if (documentVersion == null && previousReportVersion != null && reportVersion < previousReportVersion) {
                    return;
                }
                lastReportVersions.put(uri, reportVersion);
            }

            List<DiagnosticEntry> current = new ArrayList<>();
            JsonNode diagnostics = params.path("diagnostics");
            if (diagnostics.isArray()) {
                Set<String> reportKeys = new HashSet<>();
                for (JsonNode diagnostic : diagnostics) {
                    JsonNode range = diagnostic.path("range");
                    JsonNode start = range.path("start");
                    int line = start.path("line").asInt();
                    int character = start.path("character").asInt();
                    int endLine = range.path("end").path("line").asInt(line);
                    int endCharacter = range.path("end").path("character").asInt(character);
                    Integer severity = diagnostic.hasNonNull("severity") ? diagnostic.path("severity").asInt() : null;
                    String message = diagnostic.path("message").asText();
                    String key = line + ":" + character + ":" + endLine + ":" + endCharacter
                            + ":" + severity + ":" + message;
                    if (reportKeys.add(key)) {
                        current.add(new DiagnosticEntry(uri, key, relativePathFromUri(uri), line, character, severity, message));
                    }
                }
            }

            latestReports.put(uri, current);
            Set<String> alreadyShown = shownKeys.computeIfAbsent(uri, ignored -> new HashSet<>());
            Set<String> currentKeys = new HashSet<>();
            current.forEach(diagnostic -> currentKeys.add(diagnostic.key()));
            alreadyShown.retainAll(currentKeys);

            List<DiagnosticEntry> newDiagnostics = current.stream()
                    .filter(diagnostic -> !alreadyShown.contains(diagnostic.key()))
                    .toList();
            if (newDiagnostics.isEmpty()) {
                pending.remove(uri);
            } else {
                pending.put(uri, new ArrayList<>(newDiagnostics));
            }
            if (current.isEmpty()) {
                shownKeys.put(uri, new HashSet<>());
            }
        }

        private String takeNewDiagnostics() {
            if (pending.isEmpty()) {
                return "";
            }

            Map<String, List<DiagnosticEntry>> byFile = new LinkedHashMap<>();
            for (List<DiagnosticEntry> diagnostics : pending.values()) {
                for (DiagnosticEntry diagnostic : diagnostics) {
                    byFile.computeIfAbsent(diagnostic.relativePath(), ignored -> new ArrayList<>()).add(diagnostic);
                }
            }

            StringBuilder output = new StringBuilder(
                    "<new-diagnostics>The following new diagnostic issues were detected:\n\n");
            byFile.forEach((file, diagnostics) -> {
                output.append(file).append(':').append('\n');
                for (DiagnosticEntry diagnostic : diagnostics) {
                    output.append("  [Line ").append(diagnostic.line() + 1).append(':')
                            .append(diagnostic.character() + 1).append(']');
                    if (diagnostic.severity() != null) {
                        output.append(" [").append(severityName(diagnostic.severity())).append(']');
                    }
                    output.append(' ').append(diagnostic.message()).append('\n');
                }
                output.append('\n');
            });
            output.append("</new-diagnostics>");

            for (String uri : pending.keySet()) {
                Set<String> currentKeys = new HashSet<>();
                latestReports.getOrDefault(uri, List.of()).forEach(diagnostic -> currentKeys.add(diagnostic.key()));
                shownKeys.put(uri, currentKeys);
            }
            pending.clear();
            return output.toString();
        }

        private boolean hasNewDiagnostics() {
            return !pending.isEmpty();
        }

        private static String severityName(int severity) {
            return switch (severity) {
                case 1 -> "Error";
                case 2 -> "Warning";
                case 3 -> "Information";
                case 4 -> "Hint";
                default -> "Severity " + severity;
            };
        }
    }

    private static String invokeSkill(OpenAIClient client, LSPClient lspClient, Map<String, Integer> lspDocumentVersions, DiagnosticTracker diagnosticTracker, SkillTool arguments) {
        Path skillFile = findSkillFile(arguments.name);
        if (skillFile == null) {
            return "Skill not found: " + arguments.name;
        }

        SkillDocument skill = loadSkill(skillFile, arguments.name);
        String body = substituteSkillArguments(skill.body(), arguments.args == null ? "" : arguments.args);
        if (!"fork".equals(skill.metadata().get("context"))) {
            return body;
        }

        List<ChatCompletionMessageParam> subagentMessages = new ArrayList<>();
        addUserMessage(subagentMessages, body);
        String answer = runAgentLoop(client, lspClient, lspDocumentVersions, diagnosticTracker, subagentMessages);
        return "Skill " + arguments.name + " ran in a separate context and returned: " + answer;
    }

    private static String executeBash(String command) {
        try {
            Process process = new ProcessBuilder("bash", "-c", command)
                    .directory(Path.of("").toAbsolutePath().toFile())
                    .redirectErrorStream(true)
                    .start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int exitCode = process.waitFor();
            if (exitCode == 0) {
                return output;
            }
            return "Command failed with exit code " + exitCode + ".\n" + output;
        } catch (java.io.IOException e) {
            return "Failed to execute command: " + e.getMessage();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "Command execution was interrupted.";
        }
    }

    private static String loadSkillsPrompt() {
        Path skillsDirectory = Path.of(".claude", "skills");
        if (!Files.isDirectory(skillsDirectory)) {
            return null;
        }

        List<String> skillDescriptions = new ArrayList<>();
        try (Stream<Path> skillFolders = Files.list(skillsDirectory)) {
            for (Path skillFolder : skillFolders.filter(Files::isDirectory).sorted(Comparator.comparing(Path::toString)).toList()) {
                Path skillFile = skillFolder.resolve("SKILL.md");
                if (!Files.isRegularFile(skillFile)) {
                    continue;
                }

                Map<String, String> metadata = parseSkillFrontmatter(Files.readAllLines(skillFile, StandardCharsets.UTF_8));
                String name = metadata.get("name");
                String description = metadata.get("description");
                if (name != null && !name.isBlank() && description != null && !description.isBlank()) {
                    skillDescriptions.add("- " + name + ": " + description);
                }
            }
        } catch (java.io.IOException e) {
            throw new RuntimeException("failed to load skills", e);
        }

        if (skillDescriptions.isEmpty()) {
            return null;
        }
        return "You have access to the following skills:\n\n"
                + String.join("\n", skillDescriptions)
                + "\n\nIf a skill matches the user's request, call the Skill tool with its name\n"
                + "and follow the instructions it returns.\n";
    }

    private static Map<String, String> parseSkillFrontmatter(List<String> lines) {
        Map<String, String> metadata = new java.util.HashMap<>();
        if (lines.isEmpty() || !lines.get(0).strip().equals("---")) {
            return metadata;
        }

        int frontmatterEnd = findFrontmatterEnd(lines);
        if (frontmatterEnd < 0) {
            return metadata;
        }

        for (int i = 1; i < frontmatterEnd; i++) {
            String line = lines.get(i);
            if (line.isBlank() || Character.isWhitespace(line.charAt(0))) {
                continue;
            }

            int colon = line.indexOf(':');
            if (colon <= 0) {
                continue;
            }

            String key = line.substring(0, colon).trim();
            if (!key.equals("name") && !key.equals("description") && !key.equals("context")) {
                continue;
            }

            String value = line.substring(colon + 1).trim();
            if (key.equals("description") && value.matches("[|>][+-]?") ) {
                boolean folded = value.startsWith(">");
                List<String> contentLines = new ArrayList<>();
                int j = i + 1;
                while (j < frontmatterEnd && (lines.get(j).isBlank() || Character.isWhitespace(lines.get(j).charAt(0)))) {
                    contentLines.add(lines.get(j));
                    j++;
                }
                value = parseBlockScalar(contentLines, folded);
                i = j - 1;
            } else if (key.equals("description") && value.isEmpty()) {
                List<String> contentLines = new ArrayList<>();
                int j = i + 1;
                while (j < frontmatterEnd && (lines.get(j).isBlank() || Character.isWhitespace(lines.get(j).charAt(0)))) {
                    contentLines.add(lines.get(j));
                    j++;
                }
                value = String.join(" ", contentLines.stream().map(String::trim).filter(s -> !s.isEmpty()).toList());
                i = j - 1;
            } else {
                value = parseScalar(value);
            }
            metadata.put(key, value);
        }
        return metadata;
    }

    private static void addUserMessages(OpenAIClient client, LSPClient lspClient, Map<String, Integer> lspDocumentVersions, DiagnosticTracker diagnosticTracker, List<ChatCompletionMessageParam> messages, String prompt) {
        if (!prompt.startsWith("/")) {
            addUserMessage(messages, prompt);
            return;
        }

        Path skillsDirectory = Path.of(".claude", "skills").toAbsolutePath().normalize();
        List<String> skillBodies = new ArrayList<>();
        String arguments = "";
        Matcher tokens = Pattern.compile("\\S+").matcher(prompt);
        while (tokens.find()) {
            String token = tokens.group();
            if (!token.startsWith("/") || token.length() == 1) {
                arguments = prompt.substring(tokens.start());
                break;
            }

            String skillName = token.substring(1);
            if (skillName.contains("/") || skillName.contains("\\")) {
                arguments = prompt.substring(tokens.start());
                break;
            }

            Path skillDirectory = skillsDirectory.resolve(skillName).normalize();
            if (!skillDirectory.startsWith(skillsDirectory)) {
                arguments = prompt.substring(tokens.start());
                break;
            }
            Path skillFile = skillDirectory.resolve("SKILL.md");
            if (!Files.isRegularFile(skillFile)) {
                arguments = prompt.substring(tokens.start());
                break;
            }
            SkillDocument skill = loadSkill(skillFile, skillName);
            if ("fork".equals(skill.metadata().get("context"))) {
                SkillTool forkArguments = new SkillTool();
                forkArguments.name = skillName;
                forkArguments.args = prompt.substring(tokens.end()).strip();
                addUserMessage(messages, invokeSkill(client, lspClient, lspDocumentVersions, diagnosticTracker, forkArguments));
                return;
            }
            skillBodies.add(skill.body());
        }

        if (skillBodies.isEmpty()) {
            addUserMessage(messages, prompt);
            return;
        }
        for (String body : skillBodies) {
            addUserMessage(messages, substituteSkillArguments(body, arguments));
        }
    }

    private static void addUserMessage(List<ChatCompletionMessageParam> messages, String content) {
        messages.add(ChatCompletionMessageParam.ofUser(
                com.openai.models.chat.completions.ChatCompletionUserMessageParam.builder()
                        .content(content)
                        .build()));
    }

    private static void addSystemMessage(List<ChatCompletionMessageParam> messages, String content) {
        messages.add(ChatCompletionMessageParam.ofSystem(
                ChatCompletionSystemMessageParam.builder()
                        .content(content)
                        .build()));
    }

    private static SkillDocument loadSkill(Path skillFile, String skillName) {
        try {
            List<String> lines = Files.readAllLines(skillFile, StandardCharsets.UTF_8);
            int frontmatterEnd = findFrontmatterEnd(lines);
            if (frontmatterEnd < 0) {
                throw new RuntimeException("skill has invalid frontmatter: " + skillName);
            }
            Map<String, String> metadata = parseSkillFrontmatter(lines);
            String body = String.join("\n", lines.subList(frontmatterEnd + 1, lines.size())).strip();
            return new SkillDocument(body, metadata);
        } catch (java.io.IOException e) {
            throw new RuntimeException("failed to load skill: " + skillName, e);
        }
    }

    private static Path findSkillFile(String skillName) {
        if (skillName == null || skillName.isBlank() || skillName.contains("/") || skillName.contains("\\")) {
            return null;
        }

        Path skillsDirectory = Path.of(".claude", "skills").toAbsolutePath().normalize();
        Path skillDirectory = skillsDirectory.resolve(skillName).normalize();
        if (!skillDirectory.startsWith(skillsDirectory)) {
            return null;
        }
        Path skillFile = skillDirectory.resolve("SKILL.md");
        return Files.isRegularFile(skillFile) ? skillFile : null;
    }

    private static String substituteSkillArguments(String body, String arguments) {
        String[] positionalArguments = arguments.isBlank() ? new String[0] : arguments.split("\\s+");
        Pattern placeholderPattern = Pattern.compile("\\$ARGUMENTS(?:\\[(\\d+)\\])?|\\$(\\d+)");
        Matcher matcher = placeholderPattern.matcher(body);
        StringBuffer substituted = new StringBuffer();
        while (matcher.find()) {
            String replacement;
            if (matcher.group(1) != null) {
                replacement = positionalArgument(positionalArguments, matcher.group(1));
            } else if (matcher.group(2) != null) {
                replacement = positionalArgument(positionalArguments, matcher.group(2));
            } else {
                replacement = arguments;
            }
            matcher.appendReplacement(substituted, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(substituted);
        return substituted.toString();
    }

    private static String positionalArgument(String[] arguments, String indexText) {
        try {
            int index = Integer.parseInt(indexText);
            return index >= 0 && index < arguments.length ? arguments[index] : "";
        } catch (NumberFormatException e) {
            return "";
        }
    }

    private static int findFrontmatterEnd(List<String> lines) {
        if (lines.isEmpty() || !lines.get(0).strip().equals("---")) {
            return -1;
        }
        for (int i = 1; i < lines.size(); i++) {
            if (lines.get(i).strip().equals("---")) {
                return i;
            }
        }
        return -1;
    }

    private static String parseBlockScalar(List<String> lines, boolean folded) {
        int indentation = lines.stream()
                .filter(line -> !line.isBlank())
                .mapToInt(line -> line.length() - line.stripLeading().length())
                .min()
                .orElse(0);
        List<String> values = lines.stream()
                .map(line -> line.isBlank() ? "" : line.substring(Math.min(indentation, line.length())))
                .toList();
        String separator = folded ? " " : "\n";
        return String.join(separator, values).trim();
    }

    private static String parseScalar(String value) {
        if (value.length() >= 2 && value.startsWith("'") && value.endsWith("'")) {
            return value.substring(1, value.length() - 1).replace("''", "'");
        }
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            return value.substring(1, value.length() - 1)
                    .replace("\\n", "\n")
                    .replace("\\\"", "\"")
                    .replace("\\\\", "\\");
        }
        int comment = value.indexOf(" #");
        return comment >= 0 ? value.substring(0, comment).trim() : value;
    }
}
