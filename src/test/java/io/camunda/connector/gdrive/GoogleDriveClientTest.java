/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH
 * under one or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information regarding copyright
 * ownership. Camunda licenses this file to you under the Apache License,
 * Version 2.0; you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.camunda.connector.gdrive;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.google.api.services.drive.Drive;
import com.google.api.services.drive.model.File;
import java.io.IOException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class GoogleDriveClientTest {

  private GoogleDriveClient client;
  private Drive.Files files;

  @BeforeEach
  public void before() {
    Drive drive = Mockito.mock(Drive.class);
    client = new GoogleDriveClient(drive);
    files = Mockito.mock(Drive.Files.class);
    when(drive.files()).thenReturn(files);
  }

  @DisplayName("Should create google metaData file")
  @Test
  public void createWithMetadata_shouldCreateFolderWithMetaData() throws IOException {
    // Given
    Drive.Files.Create create = Mockito.mock(Drive.Files.Create.class);
    when(files.create(any(File.class))).thenReturn(create);
    when(create.execute()).thenReturn(new File());
    // When
    File byMetaData = client.createWithMetadata(new File());
    // Then
    assertThat(byMetaData).isNotNull();
  }
}
