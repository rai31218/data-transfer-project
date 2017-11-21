/*
 * Copyright 2017 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.dataportabilityproject.webapp;

import com.google.common.base.Enums;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableMap;
import java.util.Map;
import org.dataportabilityproject.ServiceProviderRegistry;
import org.dataportabilityproject.shared.LogUtils;
import org.dataportabilityproject.shared.PortableDataType;
import org.dataportabilityproject.shared.auth.AuthFlowInitiator;
import org.dataportabilityproject.shared.auth.OnlineAuthDataGenerator;
import org.dataportabilityproject.job.JobManager;
import org.dataportabilityproject.job.PortabilityJob;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Controller to render the import authorization after the export authorization step has completed.
 */
@RestController
public class ImportSetupController {
  @Autowired
  private ServiceProviderRegistry registry;

  @Autowired
  private JobManager jobManager;

  @RequestMapping("/_/importSetup")
  public Map<String, String> importSetup(
      @CookieValue(value = JsonKeys.ID_COOKIE_KEY, required = true) String encodedIdCookie) throws Exception {
    // TODO: move to interceptor to redirect
    Preconditions.checkArgument(!Strings.isNullOrEmpty(encodedIdCookie), "Encoded Id cookie required");

    // Valid job must be present
    String jobId = JobUtils.decodeId(encodedIdCookie);
    PortabilityJob job = jobManager.findExistingJob(jobId);
    Preconditions.checkState(null != job, "existingJob not found for jobId: %s", jobId);

    LogUtils.log("importSetup, job: %s", job);

    String exportService = job.exportService();
    Preconditions.checkState(!Strings.isNullOrEmpty(job.exportService()), "Export service is invalid");
    Preconditions.checkState(job.exportAuthData() != null, "Export AuthData is required");
    String importService = job.importService();
    Preconditions.checkState(!Strings.isNullOrEmpty(job.importService()), "Import service is invalid");

    // TODO: ensure import auth data doesn't exist when this is called in the auth flow
   Preconditions.checkState(job.importAuthData() == null, "Import AuthData should not exist");

    // Obtain the OnlineAuthDataGenerator
    OnlineAuthDataGenerator generator = registry.getOnlineAuth(job.importService(), getDataType(job.dataType()));

    // Auth authUrl
    AuthFlowInitiator authFlowInitiator = generator.generateAuthUrl(JobUtils.encodeId(job));

    // Store authUrl - this page is only valid for Import Authorization flow, so isExport needs to
    // be set to false to store initialImportAuthData
    // TODO: support both import and export.
    if (authFlowInitiator.initialAuthData() != null) {
      PortabilityJob jobBeforeInitialData = lookupJob(jobId);
      PortabilityJob updatedJob = JobUtils
          .setInitialAuthData(job, authFlowInitiator.initialAuthData(), false);
      jobManager.updateJob(updatedJob);
    }

    // Send the authUrl for the client to redirect to service authorization
    LogUtils.log("Import auth authUrl sent to client: %s", authFlowInitiator.authUrl());

    return ImmutableMap.<String, String>builder()
        .put(JsonKeys.DATA_TYPE, job.dataType())
        .put(JsonKeys.EXPORT_SERVICE, job.exportService())
        .put(JsonKeys.IMPORT_SERVICE, job.importService())
        .put(JsonKeys.IMPORT_AUTH_URL, authFlowInitiator.authUrl())
        .build();
  }

  /** Looks up job and does checks that it exists. */
  private PortabilityJob lookupJob(String jobId) {
    PortabilityJob job = jobManager.findExistingJob(jobId);
    Preconditions.checkState(null != job, "existingJob not found for jobId: %s", jobId);
    return job;
  }

  /** Parse the data type .*/
  private static PortableDataType getDataType(String dataType) {
    com.google.common.base.Optional<PortableDataType> dataTypeOption = Enums
        .getIfPresent(PortableDataType.class, dataType);
    Preconditions.checkState(dataTypeOption.isPresent(), "Data type required");
    return dataTypeOption.get();
  }
}
