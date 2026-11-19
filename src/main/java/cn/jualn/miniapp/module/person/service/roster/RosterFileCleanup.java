package cn.jualn.miniapp.module.person.service.roster;

import cn.jualn.miniapp.module.person.mapper.QualificationMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;

/** Bounded ciphertext cleanup; formal source rows, events and receipts are permanent. */
@Service @RequiredArgsConstructor
public class RosterFileCleanup {
    private final QualificationMapper mapper;
    @Scheduled(fixedDelayString="${people-qualification.file-cleanup-delay-ms:60000}")
    @Transactional
    public void purge() {mapper.purgeFiles();}
}
