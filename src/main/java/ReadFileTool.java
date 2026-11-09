import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

@JsonClassDescription("Read and return the contents of a file")
public class ReadFileTool {

    @JsonPropertyDescription("The path to the file to read")
    @JsonProperty("file_path")
    public String filePath;
}