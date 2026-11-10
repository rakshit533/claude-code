import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.JsonValue;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.ChatCompletionMessageParam;
import com.openai.models.chat.completions.ChatCompletionToolMessageParam;

import java.util.Map;
import java.util.List;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;

public class Main {
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
        messages.add(ChatCompletionMessageParam.ofUser(
                com.openai.models.chat.completions.ChatCompletionUserMessageParam.builder()
                        .content(prompt)
                        .build()));

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
                                                                            "required", List.of("file_path", "content")))))))
                            .build());
            if (response.choices().isEmpty()) {
                throw new RuntimeException("no choices in response");
            }

            var message = response.choices().get(0).message();
            messages.add(ChatCompletionMessageParam.ofAssistant(message.toParam()));

            var toolCalls = message.toolCalls();
            if (toolCalls.isEmpty() || toolCalls.get().isEmpty()) {
                System.out.print(message.content().orElse(""));
                return;
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
}
