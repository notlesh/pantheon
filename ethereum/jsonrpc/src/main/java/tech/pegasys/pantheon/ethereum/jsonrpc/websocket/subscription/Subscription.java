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
package tech.pegasys.pantheon.ethereum.jsonrpc.websocket.subscription;

import tech.pegasys.pantheon.ethereum.jsonrpc.websocket.subscription.request.SubscriptionType;

import java.util.Objects;

import com.google.common.base.MoreObjects;

public class Subscription {

  private final Long id;
  private final SubscriptionType subscriptionType;
  private final Boolean includeTransaction;

  public Subscription(
      final Long id, final SubscriptionType subscriptionType, final Boolean includeTransaction) {
    this.id = id;
    this.subscriptionType = subscriptionType;
    this.includeTransaction = includeTransaction;
  }

  public SubscriptionType getSubscriptionType() {
    return subscriptionType;
  }

  public Long getId() {
    return id;
  }

  public Boolean getIncludeTransaction() {
    return includeTransaction;
  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this)
        .add("id", id)
        .add("subscriptionType", subscriptionType)
        .toString();
  }

  public boolean isType(final SubscriptionType type) {
    return this.subscriptionType == type;
  }

  @Override
  public boolean equals(final Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    final Subscription that = (Subscription) o;
    return Objects.equals(id, that.id) && subscriptionType == that.subscriptionType;
  }

  @Override
  public int hashCode() {
    return Objects.hash(id, subscriptionType);
  }
}
