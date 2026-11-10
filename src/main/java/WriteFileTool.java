import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

@JsonClassDescription("Write content to a file")
public class WriteFileTool {

    @JsonPropertyDescription("The path of the file to write to")
    @JsonProperty("file_path")
    public String filePath;

    @JsonPropertyDescription("The content to write to the file")
    @JsonProperty("content")
    public String content;
}
