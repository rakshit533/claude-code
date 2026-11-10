import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

@JsonClassDescription("Execute a shell command")
public class BashTool {

    @JsonPropertyDescription("The command to execute")
    @JsonProperty("command")
    public String command;
}
