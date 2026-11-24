package cn.jualn.miniapp.third.cos.client;

import cn.jualn.miniapp.third.cos.config.*;
import cn.jualn.miniapp.common.exception.ContractProblemException;
import com.qcloud.cos.COSClient;
import com.qcloud.cos.model.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CosPrivateStorageTest {
    COSClient sdk=mock(COSClient.class);
    CosProperties properties=new CosProperties("unused","unused","public-123","ap-test","https://cdn.example",600,"cdn.example");
    CosStorageBuckets buckets=new CosStorageBuckets(properties,sdk);
    CosPrivateStorageTest(){ReflectionTestUtils.setField(buckets,"privateBucket","private-123");}
    void privateEvidence() {
        var acl=new AccessControlList();acl.setOwner(new Owner("123","owner"));when(sdk.getBucketAcl("private-123")).thenReturn(acl);
        var policy=new BucketPolicy();policy.setPolicyText("{\"version\":\"2.0\",\"statement\":[]}");when(sdk.getBucketPolicy("private-123")).thenReturn(policy);
    }
    CosClient client() {
        var provider=mock(org.springframework.beans.factory.ObjectProvider.class);when(provider.getObject()).thenReturn(buckets);when(provider.getIfAvailable()).thenReturn(buckets);
        return new CosClient(sdk,properties,provider);
    }
    @Test void privateNamespaceNeverFallsBackOrCreatesPublicUrl() {
        assertEquals("public-123",buckets.forKey("user/avatar"));assertEquals("private-123",buckets.forKey("post/upload/a"));
        assertThrows(IllegalArgumentException.class,()->client().buildPublicUrl("post/upload/a"));
        ReflectionTestUtils.setField(buckets,"privateBucket","");assertThrows(ContractProblemException.class,()->buckets.forKey("post/upload/a"));
    }
    @Test void anonymousAclPolicyOrUnknownPolicyFailClosed() {
        assertThrows(ContractProblemException.class,()->buckets.requirePrivate("post/upload/a"));privateEvidence();assertDoesNotThrow(()->buckets.requirePrivate("post/upload/a"));
        var policy=new BucketPolicy();policy.setPolicyText("{\"statement\":[{\"effect\":\"allow\",\"principal\":{\"qcs\":[\"qcs::cam::anyone:anyone\"]},\"action\":[\"name/cos:GetObject\"]}]}");when(sdk.getBucketPolicy("private-123")).thenReturn(policy);
        assertThrows(ContractProblemException.class,()->buckets.requirePrivate("post/upload/a"));
        when(sdk.getBucketPolicy("private-123")).thenReturn(null);assertThrows(ContractProblemException.class,()->buckets.requirePrivate("post/upload/a"));
        var missing=new com.qcloud.cos.exception.CosServiceException("missing");missing.setStatusCode(404);when(sdk.getBucketPolicy("private-123")).thenThrow(missing);assertDoesNotThrow(()->buckets.requirePrivate("post/upload/a"));
        when(sdk.getBucketAcl("private-123")).thenThrow(missing);assertThrows(ContractProblemException.class,()->buckets.requirePrivate("post/upload/a"));
    }
    @Test void copyAndDownloadUseExactPrivateBucketAndCredentialScope() throws Exception {
        privateEvidence();var client=client();client.copyObject("activity/upload/9/source","post/retained/new");
        var copy=org.mockito.ArgumentCaptor.forClass(CopyObjectRequest.class);verify(sdk).copyObject(copy.capture());
        assertEquals("public-123",copy.getValue().getSourceBucketName());assertEquals("private-123",copy.getValue().getDestinationBucketName());assertEquals(CannedAccessControlList.Private,copy.getValue().getCannedAccessControlList());
        client.openObject("post/retained/new",2,5);var read=org.mockito.ArgumentCaptor.forClass(GetObjectRequest.class);verify(sdk).getObject(read.capture());assertEquals("private-123",read.getValue().getBucketName());assertArrayEquals(new long[]{2,5},read.getValue().getRange());
        String policy=client.uploadPolicy(java.util.List.of("post/upload/9/source"));assertTrue(policy.contains("private-123/post/upload/9/source"));assertFalse(policy.contains("GetObject"));
        assertThrows(IllegalArgumentException.class,()->client.uploadPolicy(java.util.List.of("post/upload/a","activity/upload/b")));
    }
}
