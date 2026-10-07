package cn.jualn.miniapp.module.activity.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.OffsetDateTime;
import java.util.List;
import lombok.Data;
import lombok.EqualsAndHashCode;
import cn.jualn.miniapp.module.timeline.vo.TimelineScheduleVO;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
@EqualsAndHashCode(callSuper = true)
public class ActivityDetailResourceVO extends ActivitySummaryVO {
    private List<TimelineNode> timeline;
    private List<Section> sections;
    private List<Action> actions;
    private List<Contact> contacts;
    private List<Attachment> attachments;
    private RegistrationForm registrationForm;
    private String formVersion;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TimelineNode(String nodeKey, String type, String title, String description,
            TimelineScheduleVO schedule, String location, int displayOrder) {
    }
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Section(String sectionKey, String title, String content, String format, int displayOrder) {
    }
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Action(String actionKey, String type, String title, String description,
            String url, String attachmentId, int displayOrder) {
    }
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Contact(String contactKey, String name, String contact, String remark) {
    }
    public record RegistrationForm(List<FormField> fields, boolean allowModification) {
    }
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record FormField(String fieldKey, String label, String purpose, String type,
            boolean required, int displayOrder, String helpText, Integer maxLength, List<FormOption> options) {
    }
    public record FormOption(String optionKey, String label) {
    }
}
