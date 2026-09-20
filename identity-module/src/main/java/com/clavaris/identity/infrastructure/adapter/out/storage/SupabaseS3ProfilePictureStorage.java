package com.clavaris.identity.infrastructure.adapter.out.storage;

import com.clavaris.identity.application.usecase.updateaccountprofilepicture.ProfilePictureStorage;
import com.clavaris.identity.application.usecase.updateaccountprofilepicture.ProfilePictureStorageException;
import com.clavaris.identity.application.usecase.updateaccountprofilepicture.StoredProfilePicture;
import java.net.URI;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * ADR-0026: implements {@link ProfilePictureStorage} against Supabase Storage's S3-compatible
 * endpoint via the standard AWS SDK v2 {@link S3Client} — no proprietary Supabase SDK dependency.
 * {@code forcePathStyle(true)} is required for any non-AWS S3-compatible endpoint (confirmed by
 * Supabase's own Storage documentation): AWS's default virtual-hosted-style addressing ({@code
 * bucket.endpoint/key}) assumes a real AWS-issued DNS wildcard certificate that a
 * self-hosted/third-party endpoint like Supabase's never has, so the client must instead address
 * objects path-style ({@code endpoint/bucket/key}).
 *
 * <p>Never computes its own key — every caller passes a stable, already-unique {@code ownerKey}
 * (see {@link ProfilePictureStorage}'s own Javadoc), so a replace overwrites in place with no
 * orphaned previous object to separately clean up, and {@link #delete} always has exactly one
 * object to remove.
 */
@Component
public class SupabaseS3ProfilePictureStorage implements ProfilePictureStorage {

  private final S3Client s3Client;
  private final String bucket;

  @Autowired
  /* package */ SupabaseS3ProfilePictureStorage(
      @Value("${clavaris.profile-picture.supabase.s3-endpoint}") final String endpoint,
      @Value("${clavaris.profile-picture.supabase.s3-region:us-east-1}") final String region,
      @Value("${clavaris.profile-picture.supabase.access-key-id}") final String accessKeyId,
      @Value("${clavaris.profile-picture.supabase.secret-access-key}") final String secretAccessKey,
      @Value("${clavaris.profile-picture.supabase.bucket}") final String bucket) {
    this(
        S3Client.builder()
            .endpointOverride(URI.create(endpoint))
            .region(Region.of(region))
            .forcePathStyle(true)
            .credentialsProvider(
                StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(accessKeyId, secretAccessKey)))
            .build(),
        bucket);
  }

  /* package */ SupabaseS3ProfilePictureStorage(final S3Client s3Client, final String bucket) {
    this.s3Client = s3Client;
    this.bucket = bucket;
  }

  @Override
  public String upload(final String ownerKey, final byte[] content, final String contentType) {
    try {
      s3Client.putObject(
          PutObjectRequest.builder().bucket(bucket).key(ownerKey).contentType(contentType).build(),
          RequestBody.fromBytes(content));
      return ownerKey;
    } catch (final SdkException e) {
      throw new ProfilePictureStorageException("Failed to upload profile picture to storage", e);
    }
  }

  @Override
  public StoredProfilePicture download(final String storageKey) {
    try {
      final byte[] content =
          s3Client
              .getObjectAsBytes(GetObjectRequest.builder().bucket(bucket).key(storageKey).build())
              .asByteArray();
      final String contentType =
          s3Client
              .headObject(HeadObjectRequest.builder().bucket(bucket).key(storageKey).build())
              .contentType();
      return new StoredProfilePicture(content, contentType);
    } catch (final SdkException e) {
      throw new ProfilePictureStorageException(
          "Failed to download profile picture from storage: " + storageKey, e);
    }
  }

  @SuppressWarnings("PMD.EmptyCatchBlock")
  @Override
  public void delete(final String storageKey) {
    try {
      s3Client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(storageKey).build());
    } catch (final NoSuchKeyException e) {
      // Already gone — a no-op, not an error (this class's own Javadoc / ProfilePictureStorage's
      // own contract).
    } catch (final SdkException e) {
      throw new ProfilePictureStorageException(
          "Failed to delete profile picture from storage: " + storageKey, e);
    }
  }
}
