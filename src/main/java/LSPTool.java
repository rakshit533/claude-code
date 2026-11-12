import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

@JsonClassDescription("Returns the file, line, and character where a symbol is defined")
public class LSPTool {

    @JsonPropertyDescription("The LSP operation to perform")
    @JsonProperty("operation")
    public String operation;

    @JsonPropertyDescription("Absolute path, or a path relative to the agent's current working directory")
    @JsonProperty("filePath")
    public String filePath;

    @JsonPropertyDescription("1-based line number of the symbol")
    @JsonProperty("line")
    public Integer line;

    @JsonPropertyDescription("1-based character offset in UTF-16 code units")
    @JsonProperty("character")
    public Integer character;
}
