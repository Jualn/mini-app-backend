package cn.jualn.miniapp.module.activity.ai.prompt;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.stream.Collectors;

@Component
public class ActivityPromptBuilder {

    /**
     * 构建AI解析活动信息的提示文本。提示文本包含了任务描述、字段说明、文档内容等信息，指导AI从文档中提取活动信息并按照指定格式输出。
     * skipped参数用于提示AI哪些字段已经由用户填写了，AI只需要原样输出这些字段的值，不需要重新提取和修改。这样可以提高AI的准确性和效率，避免重复提取已经确定的信息。
     *
     * @param docText 文档内容的纯文本，已经去掉了页眉页脚等无关信息，只保留了活动详情正文部分。AI需要从这个文本中提取活动信息。
     * @param skipped 一个Map，表示用户已经填写了哪些字段以及对应的值。key是字段名（比如"title"、"category"等），value是用户填写的值。AI需要在输出时直接使用这些值，不要修改。
     * @return 一个字符串，作为AI的提示文本，包含了任务描述、字段说明、文档内容等信息。AI会根据这个提示文本来生成输出。
     */
    public String build(String docText, Map<String, Object> skipped) {
        String skipHint = skipped.isEmpty() ? "" : """
                【以下字段用户已填，直接原样输出即可，不要修改】
                %s
                """.formatted(skipped.entrySet().stream()
                .map(e -> e.getKey() + ": " + e.getValue()).collect(Collectors.joining("\n")));

        return """
                【任务】从文档中提取活动信息，如果文档包含多个活动，只提取第一个活动信息。按照以下顺序，每行输出一个 JSON，不输出其他任何内容。
                
                %s
                【字段顺序和说明】
                1. {"f":"title","v":"活动完整名称"}
                2. {"f":"category","v":数字}
                3. {"f":"organizer","v":"主办单位"}
                4. {"f":"audienceScope","v":数字}
                5. {"f":"startTime","v":"yyyy-MM-dd HH:mm:ss"}
                6. {"f":"endTime","v":"yyyy-MM-dd HH:mm:ss"}
                7. {"f":"enrollDeadline","v":"yyyy-MM-dd HH:mm:ss 或 null"}
                8. {"f":"maxParticipants","v":数字或null}
                9. {"f":"location","v":"地点或null"}
                10. {"f":"joinMethod","v":"报名方式说明或null"}
                11. {"f":"contactInfo","v":[{"name":"姓名","phone":"电话"}] 或null}
                12. {"f":"timelineItems","v":[{"label":"节点名称","description":"补充说明或null","startTime":"yyyy-MM-dd HH:mm 或 null","endTime":"yyyy-MM-dd HH:mm 或 null","sortOrder":数字}] 或null}
                
                【content 输出规则】
                13. content 为活动详情正文，必须拆分为多行 JSON 输出，不允许一次性输出完整长文本。
                每一段使用如下格式：
                {"f":"content","v":"正文片段","append"}
                
                content 拆分要求：
                按自然段、句号、分号、换行或语义完整片段拆分。
                每个 content 片段建议 80 到 200 字，不要过长。
                保留正文原有段落换行，可在 v 中使用 \\n。
                去掉页眉、页脚、无关编号、重复标题、免责声明、不能体现活动相关等无关内容。
                content 的多行输出顺序必须与原文顺序一致。
                不要再额外输出 {"f":"content","v":"完整正文"}。
                如果没有活动详情正文，输出一行：{"f":"content","v","append"}
                
                【category 取值】
                0 其他
                1 文体比赛
                2 志愿公益
                3 思政主题
                4 学术讲座
                5 体育运动
                
                【audienceScope 取值】
                使用位掩码相加：
                全院=1
                信息=2
                理工=4
                财经=8
                人文=16
                基础=32
                如果是全院或未说明，填 1。
                
                【时间规则】
                
                startTime、endTime 使用 yyyy-MM-dd HH:mm。
                时间不确定但日期明确时，时间填 00:00:00。
                找不到时间填 null。
                enrollDeadline 找不到填 null。
                
                【timelineItems 规则】
                timelineItems 用于提取活动时间线节点，如“报名截止”“初赛”“复赛”“决赛”“成绩公布”“培训”“签到”等类型。
                单点时间只填 startTime，endTime 填 null。
                时间段同时填写 startTime 和 endTime。
                label 为节点名称，也可写阶段，比较干净，多的描述在description里。
                sortOrder 按文档出现顺序或活动流程顺序从 0 开始递增。
                如果文档中没有明确时间线节点，timelineItems 填 null。
                每个节点的 label 不超过 64 字，description 不超过 255 字。
                
                【输出限制】
                
                每行必须是合法 JSON。
                禁止输出 markdown。
                禁止输出注释。
                禁止输出解释性文字。
                禁止输出代码块标记。
                除 content 外，其余字段每个字段只输出一行。
                content 可以输出多行，每行 append=true，由前端按顺序拼接。
                所有字段找不到时，v 填 null。
                
                【文档内容】
                %s
                """.formatted(skipHint, docText);
    }

}
