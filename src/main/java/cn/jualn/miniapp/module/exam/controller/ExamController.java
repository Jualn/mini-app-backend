package cn.jualn.miniapp.module.exam.controller;

import cn.jualn.miniapp.common.result.PageResult;
import cn.jualn.miniapp.common.result.Result;
import cn.jualn.miniapp.module.exam.converter.ExamConverter;
import cn.jualn.miniapp.module.exam.dto.request.ExamCreateRequest;
import cn.jualn.miniapp.module.exam.dto.request.ExamPageQuery;
import cn.jualn.miniapp.module.exam.dto.request.ExamUpdateRequest;
import cn.jualn.miniapp.module.exam.service.ExamService;
import cn.jualn.miniapp.module.exam.service.ExamSubscriptionService;
import cn.jualn.miniapp.module.exam.vo.ExamDetailVO;
import cn.jualn.miniapp.module.exam.vo.ExamSimpleVO;
import cn.jualn.miniapp.module.exam.vo.ExamVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 考试接口。
 */
@RestController
@RequestMapping("/v1/exam")
@RequiredArgsConstructor
public class ExamController {

    private final ExamService examService;
    private final ExamSubscriptionService examSubscriptionService;
    private final ExamConverter examConverter;

    @PostMapping
    public Result<Long> createExam(@RequestBody @Valid ExamCreateRequest request) {
        return Result.ok(examService.createExam(examConverter.toCreateBO(request)));
    }

    @PutMapping("/{id}")
    public Result<Void> updateExam(@PathVariable Long id, @RequestBody @Valid ExamUpdateRequest request) {
        request.setId(id);
        examService.updateExam(examConverter.toUpdateBO(request));
        return Result.ok(null);
    }

    @DeleteMapping("/{id}")
    public Result<Void> removeExam(@PathVariable Long id) {
        examService.removeExam(id);
        return Result.ok(null);
    }

    @GetMapping("/{id}")
    public Result<ExamDetailVO> getExamDetail(@PathVariable Long id) {
        return Result.ok(examConverter.toDetailVO(examService.getExamDetail(id)));
    }

    @GetMapping
    public Result<PageResult<ExamVO>> pageExam(@Valid ExamPageQuery query) {
        PageResult<cn.jualn.miniapp.module.exam.bo.ExamDetailBO> pageResult = examService.pageExam(examConverter.toPageBO(query));
        PageResult<ExamVO> result = PageResult.of(
                examConverter.toVOList(pageResult.getList()),
                pageResult.getHasMore(),
                pageResult.getNextCursor()
        );
        return Result.ok(result);
    }

    @GetMapping("/simple")
    public Result<List<ExamSimpleVO>> getExamSimple() {
        return Result.ok(examConverter.toSimpleVOList(examService.getExamSimple()));
    }

    @PostMapping("/{id}/subscribe")
    public Result<Void> subscribeExam(@PathVariable Long id) {
        examSubscriptionService.subscribeExam(id);
        return Result.ok(null);
    }

    @DeleteMapping("/{id}/subscribe")
    public Result<Void> unsubscribeExam(@PathVariable Long id) {
        examSubscriptionService.unsubscribeExam(id);
        return Result.ok(null);
    }
}
