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
package tech.pegasys.orion.testutil;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Base64;

import net.consensys.cava.bytes.Bytes;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class OrionKeyGenerator {
  private static final Logger LOG = LogManager.getLogger();

  public static KeyPair generateKeys() throws NoSuchAlgorithmException {
    final KeyPair keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    final PublicKey pubKey = keyPair.getPublic();
    final PrivateKey privKey = keyPair.getPrivate();

    LOG.debug("pubkey      : " + pubKey);
    LOG.debug("pubkey bytes: " + Bytes.wrap(pubKey.getEncoded()).toHexString());
    LOG.debug("pubkey b64  : " + Base64.getEncoder().encodeToString(pubKey.getEncoded()));

    LOG.debug("privkey      : " + privKey);
    LOG.debug("privkey bytes: " + Bytes.wrap(privKey.getEncoded()).toHexString());
    LOG.debug("privkey b64  : " + Base64.getEncoder().encodeToString(privKey.getEncoded()));

    return keyPair;
  }
}
