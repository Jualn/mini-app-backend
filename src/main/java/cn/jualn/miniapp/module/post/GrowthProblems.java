package cn.jualn.miniapp.module.post;

import cn.jualn.miniapp.common.exception.ContractProblemException;
import org.springframework.http.HttpStatus;

public final class GrowthProblems {
    private GrowthProblems() {}
    public static ContractProblemException source() { return problem(HttpStatus.SERVICE_UNAVAILABLE,"growth-source-unavailable"); }
    public static ContractProblemException media() { return problem(HttpStatus.SERVICE_UNAVAILABLE,"growth-media-unavailable"); }
    public static ContractProblemException missing() { return problem(HttpStatus.NOT_FOUND,"growth-record-not-found"); }
    public static ContractProblemException forbidden() { return problem(HttpStatus.FORBIDDEN,"growth-publishing-forbidden"); }
    public static ContractProblemException problem(HttpStatus status,String code) { return new ContractProblemException(status,"/problems/"+code,"Growth record operation unavailable"); }
}
