/*
 * Copyright 2018 ConsenSys AG.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package tech.pegasys.pantheon.ethereum.p2p.upnp;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.notNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.Before;
import org.junit.Test;
import org.jupnp.UpnpService;
import org.jupnp.controlpoint.ControlPoint;
import org.jupnp.registry.Registry;

/** Tests for {@link tech.pegasys.pantheon.ethereum.p2p.upnp.UpnpNatManager}. */
public final class UpnpNatManagerTest {

  private UpnpService mockedService;
  private Registry mockedRegistry;
  private ControlPoint mockedControlPoint;

  private UpnpNatManager upnpManager;

  @Before
  public void initialize() {

    mockedRegistry = mock(Registry.class);
    mockedControlPoint = mock(ControlPoint.class);

    mockedService = mock(UpnpService.class);
    when(mockedService.getRegistry()).thenReturn(mockedRegistry);
    when(mockedService.getControlPoint()).thenReturn(mockedControlPoint);

    upnpManager = new UpnpNatManager(mockedService);
  }

  @Test
  public void startShouldInvokeUPnPService() throws Exception {
    upnpManager.start();

    verify(mockedService).startup();
    verify(mockedRegistry).addListener(notNull());
    verify(mockedControlPoint).search(notNull());
  }

  @Test
  public void stopShouldInvokeUPnPService() throws Exception {
    upnpManager.start();
    upnpManager.stop();

    verify(mockedRegistry).removeListener(notNull());
    verify(mockedService).shutdown();
  }

  @Test
  public void startThrowsWhenAlreadyStarted() throws Exception {

    assertThatThrownBy(
            () -> {
              upnpManager.stop();
            })
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  public void stopThrowsWhenAlreadyStopped() throws Exception {
    upnpManager.start();

    assertThatThrownBy(
            () -> {
              upnpManager.start();
            })
        .isInstanceOf(IllegalStateException.class);
  }
}
