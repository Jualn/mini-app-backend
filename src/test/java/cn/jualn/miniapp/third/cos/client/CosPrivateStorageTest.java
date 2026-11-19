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
        var client=new CosClient(sdk,properties);
        var provider=mock(org.springframework.beans.factory.ObjectProvider.class);when(provider.getObject()).thenReturn(buckets);when(provider.getIfAvailable()).thenReturn(buckets);
        ReflectionTestUtils.setField(client,"storage",provider);return client;
    }
    @Test void privateNamespaceNeverFallsBackOrCreatesPublicUrl() {
        assertEquals("public-123",buckets.forKey("user/avatar"));assertEquals("private-123",buckets.forKey("private/post/a"));
        assertThrows(IllegalArgumentException.class,()->client().buildPublicUrl("private/post/a"));
        ReflectionTestUtils.setField(buckets,"privateBucket","");assertThrows(ContractProblemException.class,()->buckets.forKey("private/post/a"));
    }
    @Test void anonymousAclPolicyOrUnknownPolicyFailClosed() {
        assertThrows(ContractProblemException.class,()->buckets.requirePrivate("private/post/a"));privateEvidence();assertDoesNotThrow(()->buckets.requirePrivate("private/post/a"));
        var policy=new BucketPolicy();policy.setPolicyText("{\"statement\":[{\"effect\":\"allow\",\"principal\":{\"qcs\":[\"qcs::cam::anyone:anyone\"]},\"action\":[\"name/cos:GetObject\"]}]}");when(sdk.getBucketPolicy("private-123")).thenReturn(policy);
        assertThrows(ContractProblemException.class,()->buckets.requirePrivate("private/post/a"));
        when(sdk.getBucketPolicy("private-123")).thenReturn(null);assertThrows(ContractProblemException.class,()->buckets.requirePrivate("private/post/a"));
        var missing=new com.qcloud.cos.exception.CosServiceException("missing");missing.setStatusCode(404);when(sdk.getBucketPolicy("private-123")).thenThrow(missing);assertDoesNotThrow(()->buckets.requirePrivate("private/post/a"));
        when(sdk.getBucketAcl("private-123")).thenThrow(missing);assertThrows(ContractProblemException.class,()->buckets.requirePrivate("private/post/a"));
    }
    @Test void copyAndDownloadUseExactPrivateBucketAndCredentialScope() throws Exception {
        privateEvidence();var client=client();client.copyObject("post/9/source","private/media-effective/new");
        var copy=org.mockito.ArgumentCaptor.forClass(CopyObjectRequest.class);verify(sdk).copyObject(copy.capture());
        assertEquals("public-123",copy.getValue().getSourceBucketName());assertEquals("private-123",copy.getValue().getDestinationBucketName());assertEquals(CannedAccessControlList.Private,copy.getValue().getCannedAccessControlList());
        client.openObject("private/media-effective/new",2,5);var read=org.mockito.ArgumentCaptor.forClass(GetObjectRequest.class);verify(sdk).getObject(read.capture());assertEquals("private-123",read.getValue().getBucketName());assertArrayEquals(new long[]{2,5},read.getValue().getRange());
        String policy=client.uploadPolicy(java.util.List.of("private/post/9/source"));assertTrue(policy.contains("private-123/private/post/9/source"));assertFalse(policy.contains("GetObject"));
        assertThrows(IllegalArgumentException.class,()->client.uploadPolicy(java.util.List.of("private/post/a","post/b")));
    }
}
