import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

@JsonClassDescription("Load a skill's instructions into the conversation")
public class SkillTool {

    @JsonPropertyDescription("The name of the skill to use")
    @JsonProperty("name")
    public String name;

    @JsonPropertyDescription("Optional arguments for the skill")
    @JsonProperty("args")
    public String args;
}
