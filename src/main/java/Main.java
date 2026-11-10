import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.JsonValue;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionCreateParams;

import java.util.Map;
import java.util.List;
import java.nio.file.Files;
import java.nio.file.Path;

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

        ChatCompletion response = client.chat().completions().create(
    ChatCompletionCreateParams.builder()
        .model("anthropic/claude-haiku-4.5")
        .addUserMessage(prompt)
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
                                        "description", "The path to the file to read"
                                    )
                                ),
                                "required", List.of("file_path")
                            )
                        )
                    )
                )
            )
        )
        .build()
);
        if (response.choices().isEmpty()) {
            throw new RuntimeException("no choices in response");
        }

        var message = response.choices().get(0).message();
        var toolCalls = message.toolCalls();
        if (toolCalls.isPresent() && !toolCalls.get().isEmpty()) {
            var toolCall = toolCalls.get().get(0);
            if (!toolCall.isFunction()) {
                throw new RuntimeException("unsupported tool call type");
            }

            var function = toolCall.asFunction().function();
            if (!"Read".equals(function.name())) {
                throw new RuntimeException("unsupported tool: " + function.name());
            }

            ReadFileTool arguments = function.arguments(ReadFileTool.class);
            try {
                System.out.print(Files.readString(Path.of(arguments.filePath)));
            } catch (java.io.IOException e) {
                throw new RuntimeException("failed to read file: " + arguments.filePath, e);
            }
        } else {
            System.out.print(message.content().orElse(""));
        }
    }
}
