package io.anemos.metastore.server;

import io.anemos.metastore.MetaStore;
import io.anemos.metastore.RegistryService;
import io.anemos.metastore.config.GitProviderConfig;
import io.anemos.metastore.config.MetaStoreConfig;
import io.anemos.metastore.config.ProviderConfig;
import io.anemos.metastore.config.RegistryConfig;
import io.anemos.metastore.core.proto.ProtocUtil;
import io.anemos.metastore.core.proto.validate.ProtoDiff;
import io.anemos.metastore.core.proto.validate.ValidationResults;
import io.anemos.metastore.putils.ProtoDomain;
import io.anemos.metastore.v1alpha1.ChangeType;
import io.anemos.metastore.v1alpha1.Patch;
import io.anemos.metastore.v1alpha1.RegistryGrpc;
import io.anemos.metastore.v1alpha1.RegistryP;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.testing.GrpcCleanupRule;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.contrib.java.lang.system.EnvironmentVariables;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
@Ignore
public class ShadowE2ETest {

  @Rule public final GrpcCleanupRule grpcCleanup = new GrpcCleanupRule();

  @Rule public final EnvironmentVariables environmentVariables = new EnvironmentVariables();

  @Rule public TemporaryFolder localTempFolder = new TemporaryFolder();

  private static ProtoDomain baseKnownOption() throws IOException {
    InputStream resourceAsStream = ShadowE2ETest.class.getResourceAsStream("base_known_option.pb");
    return ProtoDomain.buildFrom(resourceAsStream);
  }

  private static ProtoDomain baseKnownOptionAddField() throws IOException {
    InputStream resourceAsStream =
        ShadowE2ETest.class.getResourceAsStream("base_known_option_add_field.pb");
    return ProtoDomain.buildFrom(resourceAsStream);
  }

  private static ProtoDomain baseAddMessageOption() throws IOException {
    InputStream resourceAsStream =
        ShadowE2ETest.class.getResourceAsStream("base_add_message_option.pb");
    return ProtoDomain.buildFrom(resourceAsStream);
  }

  private static ProtoDomain shadowDefaultFieldAdded() throws IOException {
    InputStream resourceAsStream =
        ShadowE2ETest.class.getResourceAsStream("shadow_default_field_added.pb");
    return ProtoDomain.buildFrom(resourceAsStream);
  }

  @Before
  public void before() {
    environmentVariables.set("DEBUG", "true");
  }

  @Test
  public void shadowE2ELocalFileProvider() throws Exception {
    Path metastorePath = Files.createTempDirectory("metastore");
    Path shadowrepoPath = Files.createTempDirectory("shadowrepo");

    MetaStoreConfig config = new MetaStoreConfig();
    config.setStorage(new ProviderConfig());

    ProviderConfig storage = new ProviderConfig();
    storage.setProviderClass("io.anemos.metastore.provider.LocalFileStorage");
    storage.setParameters(
        new ProviderConfig.Parameters[] {
          new ProviderConfig.Parameters("path", metastorePath.toAbsolutePath().toString())
        });

    RegistryConfig[] registries =
        new RegistryConfig[] {
          new RegistryConfig("default"),
          new RegistryConfig("shadow", "default", new String[] {"test"})
        };
    registries[1].setGit(new GitProviderConfig(shadowrepoPath.toAbsolutePath().toString()));

    config.setRegistries(registries);

    MetaStore metaStore = new MetaStore(config);

    RegistryGrpc.RegistryBlockingStub schemaRegistyStub = GetDescriptorsRegistryStub(metaStore);

    RegistryP.PutDescriptorsRequest.Builder PutDescriptorsRequest =
        RegistryP.PutDescriptorsRequest.newBuilder()
            .setRegistryName("default")
            .setMergeStrategy(
                RegistryP.Merge.newBuilder()
                    .setPackagePrefixes(
                        RegistryP.Merge.PackagePrefix.newBuilder().addPackagePrefix("test")));
    baseKnownOption()
        .iterator()
        .forEach(
            fileDescriptor ->
                PutDescriptorsRequest.addFileDescriptorProto(
                    fileDescriptor.toProto().toByteString()));
    schemaRegistyStub.putDescriptors(PutDescriptorsRequest.build());

    // check default registry insides
    ProtoDomain actualDefaultRegistry =
        ProtoDomain.buildFrom(
            new File(metastorePath.toAbsolutePath().toString().concat("/default.pb")));
    Assert.assertEquals(
        baseKnownOption().toFileDescriptorSet(), actualDefaultRegistry.toFileDescriptorSet());

    // compile shadow repo files and compare
    ProtoDomain actualShadowRepo =
        ProtocUtil.createDescriptorSet(shadowrepoPath.toAbsolutePath().toString());
    Assert.assertEquals(
        baseKnownOption().toFileDescriptorSet(), actualShadowRepo.toFileDescriptorSet());

    // add option to shadow
    RegistryP.PutDescriptorsRequest.Builder PutDescriptorsRequest2 =
        RegistryP.PutDescriptorsRequest.newBuilder().setRegistryName("shadow");
    baseAddMessageOption()
        .iterator()
        .forEach(
            fileDescriptor ->
                PutDescriptorsRequest2.addFileDescriptorProto(
                    fileDescriptor.toProto().toByteString()));
    schemaRegistyStub.putDescriptors(PutDescriptorsRequest2.build());

    // check shadow patch insides
    ValidationResults expectedResults = new ValidationResults();
    ProtoDiff protoDiff = new ProtoDiff(baseKnownOption(), baseAddMessageOption(), expectedResults);
    protoDiff.diffOnFileName("test/v1/simple.proto");
    Patch actualShadowReport =
        Patch.parseFrom(
            new FileInputStream(metastorePath.toAbsolutePath().toString() + "/shadow.pb"));
    Assert.assertEquals(
        expectedResults.createProto().getMessagePatchesMap(),
        actualShadowReport.getMessagePatchesMap());

    // add field to default
    RegistryP.PutDescriptorsRequest.Builder submitDefaultAddField =
        RegistryP.PutDescriptorsRequest.newBuilder()
            .setMergeStrategy(
                RegistryP.Merge.newBuilder()
                    .setPackagePrefixes(
                        RegistryP.Merge.PackagePrefix.newBuilder().addPackagePrefix("test").build())
                    .build());
    baseKnownOptionAddField()
        .iterator()
        .forEach(
            fileDescriptor -> {
              submitDefaultAddField.addFileDescriptorProto(fileDescriptor.toProto().toByteString());
            });

    RegistryP.PutDescriptorsResponse verifyDefaultResponse2 =
        schemaRegistyStub.putDescriptors(submitDefaultAddField.setDryRun(true).build());
    Assert.assertFalse(verifyDefaultResponse2.getValidationSummary().getDiffErrors() > 0);
    Assert.assertEquals(
        ChangeType.ADDITION,
        verifyDefaultResponse2
            .getAppliedPatch()
            .getMessagePatchesMap()
            .get("test.v1.ProtoBeamBasicMessage")
            .getFieldPatches(0)
            .getChange()
            .getChangeType());

    schemaRegistyStub.putDescriptors(submitDefaultAddField.setDryRun(false).build());
    // check shadow insides
    actualShadowReport =
        Patch.parseFrom(
            new FileInputStream(metastorePath.toAbsolutePath().toString() + "/shadow.pb"));
    Assert.assertEquals(
        expectedResults.createProto().getMessagePatchesMap(),
        actualShadowReport.getMessagePatchesMap());

    actualShadowRepo = ProtocUtil.createDescriptorSet(shadowrepoPath.toAbsolutePath().toString());
    Assert.assertEquals(
        shadowDefaultFieldAdded().toFileDescriptorSet(), actualShadowRepo.toFileDescriptorSet());
  }

  private RegistryGrpc.RegistryBlockingStub GetDescriptorsRegistryStub(MetaStore metaStore)
      throws IOException {
    String serverName = InProcessServerBuilder.generateName();
    grpcCleanup.register(
        InProcessServerBuilder.forName(serverName)
            .directExecutor()
            .addService(new RegistryService(metaStore))
            .build()
            .start());
    return RegistryGrpc.newBlockingStub(
        grpcCleanup.register(InProcessChannelBuilder.forName(serverName).directExecutor().build()));
  }
}
