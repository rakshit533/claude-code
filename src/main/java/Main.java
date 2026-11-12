import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.JsonValue;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.ChatCompletionMessageParam;
import com.openai.models.chat.completions.ChatCompletionSystemMessageParam;
import com.openai.models.chat.completions.ChatCompletionToolMessageParam;

import java.util.Map;
import java.util.List;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.stream.Stream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Main {
    private record SkillDocument(String body, Map<String, String> metadata) {}

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

        OpenAIClient client = OpenAIOkHttpClient.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .build();

        List<ChatCompletionMessageParam> messages = new ArrayList<>();
        String skillsPrompt = loadSkillsPrompt();
        if (skillsPrompt != null) {
            messages.add(ChatCompletionMessageParam.ofSystem(
                    ChatCompletionSystemMessageParam.builder()
                            .content(skillsPrompt)
                            .build()));
        }
        addUserMessages(client, messages, prompt);
        System.out.print(runAgentLoop(client, messages));
    }

    private static String runAgentLoop(OpenAIClient client, List<ChatCompletionMessageParam> messages) {
        while (true) {
            ChatCompletion response = client.chat().completions().create(
                    ChatCompletionCreateParams.builder()
                            .model("anthropic/claude-haiku-4.5")
                            .messages(messages)
                            .putAdditionalBodyProperty(
                                    "tools",
                                    JsonValue.from(
                                            List.of(
                                                    Map.of(
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
                                                                            "required", List.of("file_path")))),
                                                    Map.of(
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
                                                                            "required", List.of("file_path", "content")))),
                                                    Map.of(
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
                                                                            "required", List.of("command")))),
                                                    Map.of(
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
                                                                            "required", List.of("name")))))))
                            .build());
            if (response.choices().isEmpty()) {
                throw new RuntimeException("no choices in response");
            }

            var message = response.choices().get(0).message();
            messages.add(ChatCompletionMessageParam.ofAssistant(message.toParam()));

            var toolCalls = message.toolCalls();
            if (toolCalls.isEmpty() || toolCalls.get().isEmpty()) {
                return message.content().orElse("");
            }

            for (var toolCall : toolCalls.get()) {
                if (!toolCall.isFunction()) {
                    throw new RuntimeException("unsupported tool call type");
                }

                var functionCall = toolCall.asFunction();
                var function = functionCall.function();
                String result;
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
                        result = "File written successfully.";
                    } catch (java.io.IOException e) {
                        throw new RuntimeException("failed to write file: " + arguments.filePath, e);
                    }
                } else if ("Bash".equals(function.name())) {
                    BashTool arguments = function.arguments(BashTool.class);
                    result = executeBash(arguments.command);
                } else if ("Skill".equals(function.name())) {
                    SkillTool arguments = function.arguments(SkillTool.class);
                    result = invokeSkill(client, arguments);
                } else {
                    throw new RuntimeException("unsupported tool: " + function.name());
                }

                messages.add(ChatCompletionMessageParam.ofTool(
                        ChatCompletionToolMessageParam.builder()
                                .toolCallId(functionCall.id())
                                .content(result)
                                .build()));
            }
        }
    }

    private static String invokeSkill(OpenAIClient client, SkillTool arguments) {
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
        String answer = runAgentLoop(client, subagentMessages);
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

    private static void addUserMessages(OpenAIClient client, List<ChatCompletionMessageParam> messages, String prompt) {
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
                addUserMessage(messages, invokeSkill(client, forkArguments));
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
