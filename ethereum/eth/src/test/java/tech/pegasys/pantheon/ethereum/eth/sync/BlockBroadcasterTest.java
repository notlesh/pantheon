/*
 * Copyright 2019 ConsenSys AG.
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
package tech.pegasys.pantheon.ethereum.eth.sync;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import tech.pegasys.pantheon.ethereum.core.Block;
import tech.pegasys.pantheon.ethereum.core.BlockBody;
import tech.pegasys.pantheon.ethereum.core.BlockHeaderTestFixture;
import tech.pegasys.pantheon.ethereum.eth.manager.EthContext;
import tech.pegasys.pantheon.ethereum.eth.manager.EthPeer;
import tech.pegasys.pantheon.ethereum.eth.manager.EthPeers;
import tech.pegasys.pantheon.ethereum.eth.messages.NewBlockMessage;
import tech.pegasys.pantheon.ethereum.p2p.api.PeerConnection;
import tech.pegasys.pantheon.util.uint.UInt256;

import java.util.Collections;
import java.util.stream.Stream;

import org.junit.Test;

public class BlockBroadcasterTest {

  @Test
  public void blockPropagationUnitTest() throws PeerConnection.PeerNotConnected {
    final EthPeer ethPeer = mock(EthPeer.class);
    final EthPeers ethPeers = mock(EthPeers.class);
    when(ethPeers.streamAvailablePeers()).thenReturn(Stream.of(ethPeer));

    final EthContext ethContext = mock(EthContext.class);
    when(ethContext.getEthPeers()).thenReturn(ethPeers);

    final BlockBroadcaster blockBroadcaster = new BlockBroadcaster(ethContext);
    final Block block = generateBlock();
    final NewBlockMessage newBlockMessage =
        NewBlockMessage.create(block, block.getHeader().getDifficulty());

    blockBroadcaster.propagate(block, UInt256.ZERO);

    verify(ethPeer, times(1)).send(newBlockMessage);
  }

  @Test
  public void blockPropagationUnitTestSeenUnseen() throws PeerConnection.PeerNotConnected {
    final EthPeer ethPeer0 = mock(EthPeer.class);
    when(ethPeer0.hasSeenBlock(any())).thenReturn(true);

    final EthPeer ethPeer1 = mock(EthPeer.class);

    final EthPeers ethPeers = mock(EthPeers.class);
    when(ethPeers.streamAvailablePeers()).thenReturn(Stream.of(ethPeer0, ethPeer1));

    final EthContext ethContext = mock(EthContext.class);
    when(ethContext.getEthPeers()).thenReturn(ethPeers);

    final BlockBroadcaster blockBroadcaster = new BlockBroadcaster(ethContext);
    final Block block = generateBlock();
    final NewBlockMessage newBlockMessage =
        NewBlockMessage.create(block, block.getHeader().getDifficulty());

    blockBroadcaster.propagate(block, UInt256.ZERO);

    verify(ethPeer0, never()).send(newBlockMessage);
    verify(ethPeer1, times(1)).send(newBlockMessage);
  }

  private Block generateBlock() {
    final BlockBody body = new BlockBody(Collections.emptyList(), Collections.emptyList());
    return new Block(new BlockHeaderTestFixture().buildHeader(), body);
  }
}
