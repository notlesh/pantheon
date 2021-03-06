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
package tech.pegasys.pantheon.ethereum.mainnet;

import tech.pegasys.pantheon.ethereum.core.PrivacyParameters;
import tech.pegasys.pantheon.ethereum.vm.GasCalculator;

public class PrecompiledContractConfiguration {
  private GasCalculator gasCalculator;
  private PrivacyParameters privacyParameters;

  public PrecompiledContractConfiguration(
      final GasCalculator gasCalculator, final PrivacyParameters privacyParameters) {
    this.gasCalculator = gasCalculator;
    this.privacyParameters = privacyParameters;
  }

  public GasCalculator getGasCalculator() {
    return gasCalculator;
  }

  public void setGasCalculator(final GasCalculator gasCalculator) {
    this.gasCalculator = gasCalculator;
  }

  public PrivacyParameters getPrivacyParameters() {
    return privacyParameters;
  }

  public void setPrivacyParameters(final PrivacyParameters privacyParameters) {
    this.privacyParameters = privacyParameters;
  }
}
