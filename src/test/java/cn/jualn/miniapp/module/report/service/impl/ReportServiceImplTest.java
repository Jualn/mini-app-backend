package cn.jualn.miniapp.module.report.service.impl;

import cn.jualn.miniapp.common.constant.UserContext;
import cn.jualn.miniapp.common.enums.TargetType;
import cn.jualn.miniapp.common.enums.UserRole;
import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.common.result.PageResult;
import cn.jualn.miniapp.module.report.bo.ReportBO;
import cn.jualn.miniapp.module.report.bo.ReportCreateBO;
import cn.jualn.miniapp.module.report.bo.ReportHandleBO;
import cn.jualn.miniapp.module.report.bo.ReportPageBO;
import cn.jualn.miniapp.module.report.converter.ReportConverter;
import cn.jualn.miniapp.module.report.entity.Report;
import cn.jualn.miniapp.module.report.enums.ReportReason;
import cn.jualn.miniapp.module.report.enums.ReportStatus;
import cn.jualn.miniapp.module.report.mapper.ReportMapper;
import cn.jualn.miniapp.infrastructure.validator.TargetValidator;
import cn.jualn.miniapp.module.user.bo.UserAuthBO;
import cn.jualn.miniapp.module.user.service.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DuplicateKeyException;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReportServiceImplTest {

    @Mock
    private ReportMapper reportMapper;

    @Mock
    private ReportConverter reportConverter;

    @Mock
    private TargetValidator targetValidator;

    @Mock
    private UserService userService;

    @InjectMocks
    private ReportServiceImpl service;

    @BeforeEach
    void setUp() {
        UserContext.setUserId(10L);

        when(reportConverter.toBO(any(Report.class))).thenAnswer(invocation -> {
            Report report = invocation.getArgument(0);
            return ReportBO.builder()
                    .id(report.getId())
                    .reporterId(report.getReporterId())
                    .targetType(TargetType.fromCode(report.getTargetType()))
                    .targetId(report.getTargetId())
                    .reason(ReportReason.fromCode(report.getReason()))
                    .remark(report.getRemark())
                    .status(ReportStatus.fromCode(report.getStatus()))
                    .handlerId(report.getHandlerId())
                    .handleRemark(report.getHandleRemark())
                    .handledAt(report.getHandledAt())
                    .createdAt(report.getCreatedAt())
                    .build();
        });

        when(reportConverter.toBOList(any())).thenAnswer(invocation -> {
            List<Report> reports = invocation.getArgument(0);
            return reports.stream().map(report -> ReportBO.builder()
                    .id(report.getId())
                    .reporterId(report.getReporterId())
                    .targetType(TargetType.fromCode(report.getTargetType()))
                    .targetId(report.getTargetId())
                    .reason(ReportReason.fromCode(report.getReason()))
                    .remark(report.getRemark())
                    .status(ReportStatus.fromCode(report.getStatus()))
                    .handlerId(report.getHandlerId())
                    .handleRemark(report.getHandleRemark())
                    .handledAt(report.getHandledAt())
                    .createdAt(report.getCreatedAt())
                    .build()).toList();
        });

        when(reportConverter.toEntity(any(ReportCreateBO.class))).thenAnswer(invocation -> {
            ReportCreateBO bo = invocation.getArgument(0);
            return Report.builder()
                    .targetType(bo.getTargetType().getCode())
                    .targetId(bo.getTargetId())
                    .reason(bo.getReason().getCode())
                    .remark(bo.getRemark())
                    .build();
        });
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void createReport_shouldPersistAndReturnBO() {
        doNothing().when(targetValidator).assertExists(any(), any());

        ReportCreateBO request = ReportCreateBO.builder()
                .targetType(TargetType.POST)
                .targetId(100L)
                .reason(ReportReason.ADVERTISEMENT)
                .remark("spam")
                .build();

        ReportBO result = service.createReport(request);

        ArgumentCaptor<Report> captor = ArgumentCaptor.forClass(Report.class);
        verify(reportMapper).insert(captor.capture());
        Report saved = captor.getValue();
        assertEquals(10L, saved.getReporterId());
        assertEquals(ReportStatus.PENDING.getCode(), saved.getStatus());
        assertNotNull(saved.getCreatedAt());
        assertEquals(100L, saved.getTargetId());
        assertEquals(TargetType.POST.getCode(), saved.getTargetType());
        assertEquals(ReportReason.ADVERTISEMENT.getCode(), saved.getReason());
        assertEquals("spam", saved.getRemark());
        assertEquals(10L, result.getReporterId());
    }

    @Test
    void createReport_shouldRejectDuplicate() {
        doNothing().when(targetValidator).assertExists(any(), any());
        doThrow(new DuplicateKeyException("dup")).when(reportMapper).insert(any(Report.class));

        ReportCreateBO request = ReportCreateBO.builder()
                .targetType(TargetType.POST)
                .targetId(100L)
                .reason(ReportReason.ADVERTISEMENT)
                .build();

        assertThrows(BusinessException.class, () -> service.createReport(request));
    }

    @Test
    void pageCurrentUserReports_shouldReturnNextCursor() {
        when(reportMapper.selectList(any())).thenReturn(List.of(
                Report.builder().id(29L).reporterId(10L).status(0).createdAt(LocalDateTime.now()).build(),
                Report.builder().id(28L).reporterId(10L).status(0).createdAt(LocalDateTime.now()).build(),
                Report.builder().id(27L).reporterId(10L).status(0).createdAt(LocalDateTime.now()).build()
        ));

        ReportPageBO query = new ReportPageBO();
        query.setPageSize(2);
        query.setLastId(30L);

        PageResult<ReportBO> result = service.pageCurrentUserReports(query);

        assertEquals(2, result.getList().size());
        assertEquals(28L, result.getNextCursor());
        assertEquals(Boolean.TRUE, result.getHasMore());
        assertEquals(10L, result.getList().get(0).getReporterId());
    }

    @Test
    void handleReport_shouldUpdateHandledFields() {
        when(userService.getUserAuthInfo(10L)).thenReturn(UserAuthBO.builder().role(UserRole.ADMIN).build());
        when(reportMapper.selectById(1L)).thenReturn(Report.builder()
                .id(1L)
                .reporterId(20L)
                .status(ReportStatus.PENDING.getCode())
                .createdAt(LocalDateTime.now())
                .build());

        ReportHandleBO request = ReportHandleBO.builder()
                .status(ReportStatus.VIOLATION_HANDLED)
                .handleRemark("confirmed")
                .build();

        ReportBO result = service.handleReport(1L, request);

        ArgumentCaptor<Report> captor = ArgumentCaptor.forClass(Report.class);
        verify(reportMapper).updateById(captor.capture());
        Report updated = captor.getValue();
        assertEquals(ReportStatus.VIOLATION_HANDLED.getCode(), updated.getStatus());
        assertEquals(10L, updated.getHandlerId());
        assertEquals("confirmed", updated.getHandleRemark());
        assertNotNull(updated.getHandledAt());
        assertEquals(1L, result.getId());
        assertEquals(ReportStatus.VIOLATION_HANDLED, result.getStatus());
    }
}



