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
package tech.pegasys.pantheon.ethereum.permissioning;

import static com.google.common.base.Preconditions.checkArgument;
import static java.nio.charset.StandardCharsets.UTF_8;

import tech.pegasys.pantheon.crypto.Hash;
import tech.pegasys.pantheon.ethereum.core.Address;
import tech.pegasys.pantheon.ethereum.core.Transaction;
import tech.pegasys.pantheon.ethereum.permissioning.account.TransactionPermissioningProvider;
import tech.pegasys.pantheon.ethereum.transaction.CallParameter;
import tech.pegasys.pantheon.ethereum.transaction.TransactionSimulator;
import tech.pegasys.pantheon.ethereum.transaction.TransactionSimulatorResult;
import tech.pegasys.pantheon.util.bytes.BytesValue;
import tech.pegasys.pantheon.util.bytes.BytesValues;

import java.util.Optional;

/**
 * Controller that can read from a smart contract that exposes the permissioning call
 * transactionAllowed(address,address,uint256,uint256,uint256,bytes)
 */
public class TransactionSmartContractPermissioningController
    implements TransactionPermissioningProvider {
  private final Address contractAddress;
  private final TransactionSimulator transactionSimulator;

  // full function signature for connection allowed call
  private static final String FUNCTION_SIGNATURE =
      "transactionAllowed(address,address,uint256,uint256,uint256,bytes)";
  // hashed function signature for connection allowed call
  private static final BytesValue FUNCTION_SIGNATURE_HASH = hashSignature(FUNCTION_SIGNATURE);

  // The first 4 bytes of the hash of the full textual signature of the function is used in
  // contract calls to determine the function being called
  private static BytesValue hashSignature(final String signature) {
    return Hash.keccak256(BytesValue.of(signature.getBytes(UTF_8))).slice(0, 4);
  }

  // True from a contract is 1 filled to 32 bytes
  private static final BytesValue TRUE_RESPONSE =
      BytesValue.fromHexString(
          "0x0000000000000000000000000000000000000000000000000000000000000001");
  private static final BytesValue FALSE_RESPONSE =
      BytesValue.fromHexString(
          "0x0000000000000000000000000000000000000000000000000000000000000000");

  /**
   * Creates a permissioning controller attached to a blockchain
   *
   * @param contractAddress The address at which the permissioning smart contract resides
   * @param transactionSimulator A transaction simulator with attached blockchain and world state
   */
  public TransactionSmartContractPermissioningController(
      final Address contractAddress, final TransactionSimulator transactionSimulator) {
    this.contractAddress = contractAddress;
    this.transactionSimulator = transactionSimulator;
  }

  /**
   * Check whether a given transaction should be permitted for the current head
   *
   * @param transaction The transaction to be examined
   * @return boolean of whether or not to permit the connection to occur
   */
  @Override
  public boolean isPermitted(final Transaction transaction) {
    final BytesValue payload = createPayload(transaction);
    final CallParameter callParams =
        new CallParameter(null, contractAddress, -1, null, null, payload);

    final Optional<Boolean> contractExists =
        transactionSimulator.doesAddressExistAtHead(contractAddress);

    if (contractExists.isPresent() && !contractExists.get()) {
      throw new IllegalStateException("Transaction permissioning contract does not exist");
    }

    final Optional<TransactionSimulatorResult> result =
        transactionSimulator.processAtHead(callParams);

    if (result.isPresent()) {
      switch (result.get().getResult().getStatus()) {
        case INVALID:
          throw new IllegalStateException(
              "Transaction permissioning transaction found to be Invalid");
        case FAILED:
          throw new IllegalStateException(
              "Transaction permissioning transaction failed when processing");
        default:
          break;
      }
    }

    return result.map(r -> checkTransactionResult(r.getOutput())).orElse(false);
  }

  // Checks the returned bytes from the permissioning contract call to see if it's a value we
  // understand
  public static Boolean checkTransactionResult(final BytesValue result) {
    // booleans are padded to 32 bytes
    if (result.size() != 32) {
      throw new IllegalArgumentException("Unexpected result size");
    }

    // 0 is false
    if (result.compareTo(FALSE_RESPONSE) == 0) {
      return false;
      // 32 bytes of 1's is true
    } else if (result.compareTo(TRUE_RESPONSE) == 0) {
      return true;
      // Anything else is wrong
    } else {
      throw new IllegalStateException("Unexpected result form");
    }
  }

  // Assemble the bytevalue payload to call the contract
  public static BytesValue createPayload(final Transaction transaction) {
    return createPayload(FUNCTION_SIGNATURE_HASH, transaction);
  }

  public static BytesValue createPayload(
      final BytesValue signature, final Transaction transaction) {
    return BytesValues.concatenate(signature, encodeTransaction(transaction));
  }

  private static BytesValue encodeTransaction(final Transaction transaction) {
    return BytesValues.concatenate(
        encodeAddress(transaction.getSender()),
        encodeAddress(transaction.getTo()),
        transaction.getValue().getBytes(),
        transaction.getGasPrice().getBytes(),
        encodeLong(transaction.getGasLimit()),
        encodeBytes(transaction.getPayload()));
  }

  // Case for empty address
  private static BytesValue encodeAddress(final Optional<Address> address) {
    return encodeAddress(address.orElse(Address.wrap(BytesValue.wrap(new byte[20]))));
  }

  // Address is the 20 bytes of value left padded by 12 bytes.
  private static BytesValue encodeAddress(final Address address) {
    return BytesValues.concatenate(BytesValue.wrap(new byte[12]), address);
  }

  // long to uint256, 8 bytes big endian, so left padded by 24 bytes
  private static BytesValue encodeLong(final long l) {
    checkArgument(l >= 0, "Unsigned value must be positive");
    final byte[] longBytes = new byte[8];
    for (int i = 0; i < 8; i++) {
      longBytes[i] = (byte) ((l >> ((7 - i) * 8)) & 0xFF);
    }
    return BytesValues.concatenate(BytesValue.wrap(new byte[24]), BytesValue.wrap(longBytes));
  }

  // A bytes array is a uint256 of its length, then the bytes that make up its value, then pad to
  // next 32 bytes interval
  private static BytesValue encodeBytes(final BytesValue value) {
    final BytesValue length = encodeLong(value.size());
    final BytesValue padding = BytesValue.wrap(new byte[(32 - (value.size() % 32))]);
    return BytesValues.concatenate(length, value, padding);
  }
}
