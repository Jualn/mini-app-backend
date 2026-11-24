package cn.jualn.miniapp.module.person.service.roster;

import cn.jualn.miniapp.module.person.service.support.PersonnelValues;

import cn.jualn.miniapp.common.exception.ContractProblemException;
import cn.jualn.miniapp.module.person.bo.QualificationBO.Column;
import cn.jualn.miniapp.module.person.bo.QualificationBO.Import;
import cn.jualn.miniapp.module.person.bo.QualificationBO.Resource;

import java.util.List;
import java.util.Locale;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;

import static cn.jualn.miniapp.module.person.service.support.PersonnelValues.key;
import static cn.jualn.miniapp.module.person.service.support.PersonnelValues.problem;

/**
 * Registered upload outcome is recoverable without keeping a browser File or an in-memory task map.
 */
@Service
@RequiredArgsConstructor
public class RosterUploadService {
    private final RosterUploadCommands commands;
    private final RosterWorkbook excel;

    public Resource<Import> upload(long actor, String organization, String source, String filename, String mime, byte[] bytes, String key) {
        if (bytes == null || bytes.length > RosterWorkbook.MAX_BYTES)
            throw problem(HttpStatus.PAYLOAD_TOO_LARGE, "payload-too-large");
        if (filename == null || !filename.toLowerCase(Locale.ROOT).endsWith(".xlsx") || !"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet".equals(mime)
                || bytes.length < 4 || bytes[0] != 0x50 || bytes[1] != 0x4b || bytes[2] != 3 || bytes[3] != 4)
            throw problem(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "unsupported-media-type");
        var reservation = commands.reserve(actor, organization, source, bytes, key);
        if (reservation.completed() != null) return reservation.completed();
        final List<Column> columns;
        try {
            columns = excel.columns(bytes);
        } catch (ContractProblemException failure) {
            commands.abandonKnownParseFailure(actor, reservation.command(), key);
            throw failure;
        }
        return commands.complete(actor, reservation.command(), bytes, columns, key);
    }
}
