package com.clavaris.identity.infrastructure.adapter.out.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.clavaris.identity.application.usecase.updateaccountprofilepicture.ProfilePictureStorageException;
import com.clavaris.identity.application.usecase.updateaccountprofilepicture.StoredProfilePicture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

class SupabaseS3ProfilePictureStorageTest {

  private static final String BUCKET = "avatars-bucket";

  private S3Client s3Client;
  private SupabaseS3ProfilePictureStorage storage;

  @BeforeEach
  void setUp() {
    s3Client = mock(S3Client.class);
    storage = new SupabaseS3ProfilePictureStorage(s3Client, BUCKET);
  }

  @Test
  void uploadPutsTheObjectAndReturnsTheOwnerKey() {
    String result = storage.upload("avatars/account-1", new byte[] {1, 2, 3}, "image/png");

    assertThat(result).isEqualTo("avatars/account-1");
    ArgumentCaptor<PutObjectRequest> request = ArgumentCaptor.forClass(PutObjectRequest.class);
    verify(s3Client)
        .putObject(request.capture(), any(software.amazon.awssdk.core.sync.RequestBody.class));
    assertThat(request.getValue().bucket()).isEqualTo(BUCKET);
    assertThat(request.getValue().key()).isEqualTo("avatars/account-1");
    assertThat(request.getValue().contentType()).isEqualTo("image/png");
  }

  @Test
  void uploadWrapsAnSdkExceptionIntoAProfilePictureStorageException() {
    when(s3Client.putObject(
            any(PutObjectRequest.class), any(software.amazon.awssdk.core.sync.RequestBody.class)))
        .thenThrow(SdkException.builder().message("S3 unreachable").build());

    assertThatExceptionOfType(ProfilePictureStorageException.class)
        .isThrownBy(() -> storage.upload("avatars/account-1", new byte[] {1}, "image/png"));
  }

  @Test
  void downloadReturnsTheContentAndContentType() {
    GetObjectResponse getResponse = GetObjectResponse.builder().build();
    ResponseBytes<GetObjectResponse> responseBytes =
        ResponseBytes.fromByteArray(getResponse, new byte[] {1, 2, 3});
    when(s3Client.getObjectAsBytes(any(GetObjectRequest.class))).thenReturn(responseBytes);
    when(s3Client.headObject(any(HeadObjectRequest.class)))
        .thenReturn(HeadObjectResponse.builder().contentType("image/png").build());

    StoredProfilePicture result = storage.download("avatars/account-1");

    assertThat(result.content()).isEqualTo(new byte[] {1, 2, 3});
    assertThat(result.contentType()).isEqualTo("image/png");
  }

  @Test
  void downloadWrapsAnSdkExceptionIntoAProfilePictureStorageException() {
    when(s3Client.getObjectAsBytes(any(GetObjectRequest.class)))
        .thenThrow(SdkException.builder().message("S3 unreachable").build());

    assertThatExceptionOfType(ProfilePictureStorageException.class)
        .isThrownBy(() -> storage.download("avatars/account-1"));
  }

  @Test
  void deleteRemovesTheObject() {
    storage.delete("avatars/account-1");

    ArgumentCaptor<DeleteObjectRequest> request =
        ArgumentCaptor.forClass(DeleteObjectRequest.class);
    verify(s3Client).deleteObject(request.capture());
    assertThat(request.getValue().bucket()).isEqualTo(BUCKET);
    assertThat(request.getValue().key()).isEqualTo("avatars/account-1");
  }

  @Test
  void deleteIsANoOpWhenTheObjectIsAlreadyGone() {
    doThrow(NoSuchKeyException.builder().message("not found").build())
        .when(s3Client)
        .deleteObject(any(DeleteObjectRequest.class));

    assertThatCode(() -> storage.delete("avatars/account-1")).doesNotThrowAnyException();
  }

  @Test
  void deleteWrapsAnyOtherSdkExceptionIntoAProfilePictureStorageException() {
    doThrow(SdkException.builder().message("S3 unreachable").build())
        .when(s3Client)
        .deleteObject(any(DeleteObjectRequest.class));

    assertThatExceptionOfType(ProfilePictureStorageException.class)
        .isThrownBy(() -> storage.delete("avatars/account-1"));
  }
}
