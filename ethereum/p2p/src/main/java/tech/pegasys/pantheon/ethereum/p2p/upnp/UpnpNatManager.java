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

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import com.google.common.annotations.VisibleForTesting;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jupnp.UpnpService;
import org.jupnp.UpnpServiceImpl;
import org.jupnp.model.action.ActionInvocation;
import org.jupnp.model.message.UpnpResponse;
import org.jupnp.model.meta.RemoteDevice;
import org.jupnp.model.meta.RemoteDeviceIdentity;
import org.jupnp.model.meta.RemoteService;
import org.jupnp.model.types.UnsignedIntegerFourBytes;
import org.jupnp.model.types.UnsignedIntegerTwoBytes;
import org.jupnp.registry.Registry;
import org.jupnp.registry.RegistryListener;
import org.jupnp.support.igd.callback.GetExternalIP;
import org.jupnp.support.igd.callback.GetStatusInfo;
import org.jupnp.support.igd.callback.PortMappingAdd;
import org.jupnp.support.model.Connection;
import org.jupnp.support.model.PortMapping;

/**
 * Manages underlying UPnP library "jupnp" and provides abstractions for asynchronously interacting
 * with the NAT environment through UPnP.
 *
 * <p>This class is not thread-safe.
 */
public class UpnpNatManager {
  protected static final Logger LOG = LogManager.getLogger();

  public static final String SERVICE_TYPE_WAN_IP_CONNECTION = "WANIPConnection";

  private boolean started = false;
  private UpnpService upnpService = null;
  private RegistryListener registryListener = null;

  // internally-managed future. external queries for IP addresses will be copy()ed from this.
  private CompletableFuture<String> externalIpQueryFuture = null;

  private Map<String, RemoteService> recognizedServices;
  private String discoveredOnLocalAddress = null;

  /** Empty constructor. Creates in instance of UpnpServiceImpl. */
  public UpnpNatManager() {
    // this(new UpnpServiceImpl(new DefaultUpnpServiceConfiguration()));

    // Workaround for an issue in the jupnp library: the ExecutorService used misconfigures
    // its ThreadPoolExecutor, causing it to only launch a single thread. This prevents any work
    // from getting done (effectively a deadlock). The issue is fixed here:
    //   https://github.com/jupnp/jupnp/pull/117
    // However, this fix has not made it into any releases yet.
    // TODO: once a new release is available, remove this @Override
    this(new UpnpServiceImpl(new PantheonUpnpServiceConfiguration()));
  }

  /**
   * Constructor
   *
   * @param service is the desired instance of UpnpService.
   */
  UpnpNatManager(final UpnpService service) {
    upnpService = service;

    // prime our recognizedServices map so we can use its key-set later
    recognizedServices = new HashMap<>();
    recognizedServices.put(SERVICE_TYPE_WAN_IP_CONNECTION, null);

    // registry listener to observe new devices and look for specific services
    registryListener =
        new DefaultRegistryListener() {
          @Override
          public void remoteDeviceAdded(final Registry registry, final RemoteDevice device) {
            LOG.debug("UPnP Device discovered: " + device.getDetails().getFriendlyName());
            inspectDeviceRecursive(device, recognizedServices.keySet());
          }
        };
  }

  /**
   * Start the manager. Must not be in started state.
   *
   * @throws IllegalStateException if already started.
   */
  public synchronized void start() {
    if (started) {
      throw new IllegalStateException("Cannot start already-started service");
    }

    LOG.info("Starting UPnP Service");
    upnpService.startup();
    upnpService.getRegistry().addListener(registryListener);

    initiateExternalIpQuery();

    started = true;
  }

  /**
   * Stop the manager. Must not be in stopped state.
   *
   * @throws IllegalStateException if stopped.
   */
  public synchronized void stop() {
    if (!started) {
      throw new IllegalStateException("Cannot stop already-stopped service");
    }
    upnpService.getRegistry().removeListener(registryListener);
    upnpService.shutdown();

    started = false;
  }

  /**
   * Returns the first of the discovered services of the given type, if any.
   *
   * @param type is the type descriptor of the desired service
   * @return the first instance of the given type, or null if none
   */
  private synchronized RemoteService getService(final String type) {
    return recognizedServices.get(type);
  }

  /**
   * Get the discovered WANIPConnection service, if any.
   *
   * @return the WANIPConnection Service if we have found it, or null.
   */
  @VisibleForTesting
  synchronized RemoteService getWANIPConnectionService() {
    if (!started) {
      throw new IllegalStateException(
          "Cannot call getWANIPConnectionService() when in stopped state");
    }
    return getService(SERVICE_TYPE_WAN_IP_CONNECTION);
  }

  /**
   * Get the local address on which we discovered our external IP address.
   *
   * <p>This can be useful to distinguish which network interface the external address was
   * discovered on.
   *
   * @return the local address on which our GetExternalIP was discovered on, or null if no
   *     GetExternalIP query has been performed successfully.
   */
  public String getDiscoveredOnLocalAddress() {
    if (!started) {
      throw new IllegalStateException(
          "Cannot call getDiscoveredOnLocalAddress() when in stopped state");
    }
    return this.discoveredOnLocalAddress;
  }

  /**
   * Returns a CompletableFuture that will wait for the given service type to be discovered. No new
   * query will be performed, and if the service has already been discovered, the future will
   * complete in the very near future.
   *
   * @param serviceType is the service type to wait to be discovered.
   * @return future that will return the desired service once it is discovered, or null if the
   *     future is cancelled.
   */
  private CompletableFuture<RemoteService> discoverService(final String serviceType) {

    return CompletableFuture.supplyAsync(
        () -> {

          // wait until our thread is interrupted (assume future was cancelled)
          // or we discover the service
          while (!Thread.currentThread().isInterrupted()) {
            RemoteService service = getService(serviceType);
            if (null != service) {
              return service;
            } else {
              try {
                Thread.sleep(100);
              } catch (InterruptedException e) {
                // fall back through to "isInterrupted() check"
              }
            }
          }
          return null;
        });
  }

  /**
   * Sends a UPnP request to the discovered IGD for the external ip address.
   *
   * @return A CompletableFuture that can be used to query the result (or error).
   */
  public synchronized CompletableFuture<String> queryExternalIPAddress() {
    if (!started) {
      throw new IllegalStateException("Cannot call queryExternalIPAddress() when in stopped state");
    }
    return externalIpQueryFuture.thenApply(x -> x);
  }

  /**
   * Sends a UPnP request to the discovered IGD for the external ip address.
   *
   * <p>Note that this is not synchronized, as it is expected to be called within an
   * already-synchronized context ({@link #start()}).
   *
   * @return A CompletableFuture that can be used to query the result (or error).
   */
  private void initiateExternalIpQuery() {
    CompletableFuture<String> upnpQueryFuture = new CompletableFuture<>();

    externalIpQueryFuture =
        discoverService(SERVICE_TYPE_WAN_IP_CONNECTION)
            .thenCompose(
                service -> {

                  // our query, which will be handled asynchronously by the jupnp library
                  GetExternalIP callback =
                      new GetExternalIP(service) {

                        /**
                         * Override the success(ActionInvocation) version of success so that we can
                         * take a peek at the network interface that we discovered this on.
                         *
                         * <p>Because the underlying jupnp library omits generics info in this
                         * method signature, we must too when we override it.
                         */
                        @Override
                        @SuppressWarnings("rawtypes")
                        public void success(final ActionInvocation invocation) {
                          RemoteService service =
                              (RemoteService) invocation.getAction().getService();
                          RemoteDevice device = service.getDevice();
                          RemoteDeviceIdentity identity = device.getIdentity();

                          discoveredOnLocalAddress =
                              identity.getDiscoveredOnLocalAddress().getHostAddress();

                          super.success(invocation);
                        }

                        @Override
                        protected void success(final String result) {

                          LOG.info(
                              "External IP address {} detected for internal address {}",
                              result,
                              discoveredOnLocalAddress);

                          upnpQueryFuture.complete(result);
                        }

                        /**
                         * Because the underlying jupnp library omits generics info in this method
                         * signature, we must too when we override it.
                         */
                        @Override
                        @SuppressWarnings("rawtypes")
                        public void failure(
                            final ActionInvocation invocation,
                            final UpnpResponse operation,
                            final String msg) {
                          upnpQueryFuture.completeExceptionally(new Exception(msg));
                        }
                      };
                  upnpService.getControlPoint().execute(callback);

                  return upnpQueryFuture;
                });
  }

  /**
   * Sends a UPnP request to the discovered IGD to request status info.
   *
   * @return A CompletableFuture that can be used to query the result (or error).
   */
  public CompletableFuture<Connection.StatusInfo> queryStatusInfo() {
    if (!started) {
      throw new IllegalStateException("Cannot call queryStatusInfo() when in stopped state");
    }

    CompletableFuture<Connection.StatusInfo> upnpQueryFuture = new CompletableFuture<>();

    return discoverService(SERVICE_TYPE_WAN_IP_CONNECTION)
        .thenCompose(
            service -> {
              GetStatusInfo callback =
                  new GetStatusInfo(service) {
                    @Override
                    public void success(final Connection.StatusInfo statusInfo) {
                      upnpQueryFuture.complete(statusInfo);
                    }

                    /**
                     * Because the underlying jupnp library omits generics info in this method
                     * signature, we must too when we override it.
                     */
                    @Override
                    @SuppressWarnings("rawtypes")
                    public void failure(
                        final ActionInvocation invocation,
                        final UpnpResponse operation,
                        final String msg) {
                      upnpQueryFuture.completeExceptionally(new Exception(msg));
                    }
                  };
              upnpService.getControlPoint().execute(callback);

              return upnpQueryFuture;
            });
  }

  /**
   * Convenience function to call {@link #requestPortForward(PortMapping)} with the following
   * defaults:
   *
   * <p>enabled: true leaseDurationSeconds: 0 (indefinite) remoteHost: null internalClient: the
   * local address used to discover gateway
   *
   * <p>In addition, port is used for both internal and external port values.
   *
   * @param port is the port to be used for both internal and external port values
   * @param protocol is either "udp" or "tcp"
   * @param description is a free-form description, often displayed in router UIs
   * @return A CompletableFuture which will provide the results of the request
   */
  public CompletableFuture<Void> requestPortForward(
      final int port, final String protocol, final String description) {

    return this.requestPortForward(
        new PortMapping(
            true,
            new UnsignedIntegerFourBytes(0),
            null,
            new UnsignedIntegerTwoBytes(port),
            new UnsignedIntegerTwoBytes(port),
            null,
            PortMapping.Protocol.valueOf(protocol),
            description));
  }

  /**
   * Convenience function to avoid use of PortMapping object. Takes the same arguments as are in a
   * PortMapping object and constructs such an object for the caller.
   *
   * <p>This method chains to the {@link #requestPortForward(PortMapping)} method.
   *
   * @param enabled specifies whether or not the PortMapping is enabled
   * @param leaseDurationSeconds is the duration of the PortMapping, in seconds
   * @param remoteHost is a domain name or IP address used to filter which remote source this
   *     forwarding can apply to
   * @param externalPort is the source port (the port visible to the Internet)
   * @param internalPort is the destination port (the port to be forwarded to)
   * @param internalClient is the destination host on the local LAN
   * @param protocol is either "udp" or "tcp"
   * @param description is a free-form description, often displayed in router UIs
   * @return A CompletableFuture which will provide the results of the request
   */
  public CompletableFuture<Void> requestPortForward(
      final boolean enabled,
      final int leaseDurationSeconds,
      final String remoteHost,
      final int externalPort,
      final int internalPort,
      final String internalClient,
      final String protocol,
      final String description) {

    return this.requestPortForward(
        new PortMapping(
            enabled,
            new UnsignedIntegerFourBytes(leaseDurationSeconds),
            remoteHost,
            new UnsignedIntegerTwoBytes(externalPort),
            new UnsignedIntegerTwoBytes(internalPort),
            internalClient,
            PortMapping.Protocol.valueOf(protocol),
            description));
  }

  /**
   * Sends a UPnP request to the discovered IGD to request a port forward.
   *
   * @param portMapping is a portMapping object describing the desired port mapping parameters.
   * @return A CompletableFuture that can be used to query the result (or error).
   */
  private CompletableFuture<Void> requestPortForward(final PortMapping portMapping) {
    if (!started) {
      throw new IllegalStateException("Cannot call requestPortForward() when in stopped state");
    }

    CompletableFuture<Void> upnpQueryFuture = new CompletableFuture<>();

    return discoverService(SERVICE_TYPE_WAN_IP_CONNECTION)
        .thenCompose(
            service -> {

              // at this point, we should have the local address we discovered the IGD on,
              // so we can prime the NewInternalClient field if it was omitted
              if (null == portMapping.getInternalClient()) {
                portMapping.setInternalClient(discoveredOnLocalAddress);
              }

              // our query, which will be handled asynchronously by the jupnp library
              PortMappingAdd callback =
                  new PortMappingAdd(service, portMapping) {
                    /**
                     * Because the underlying jupnp library omits generics info in this method
                     * signature, we must too when we override it.
                     */
                    @Override
                    @SuppressWarnings("rawtypes")
                    public void success(final ActionInvocation invocation) {
                      LOG.info(
                          "Port forward request for {} {} -> {} succeeded.",
                          portMapping.getProtocol(),
                          portMapping.getInternalPort(),
                          portMapping.getExternalPort());
                      upnpQueryFuture.complete(null);
                    }

                    /**
                     * Because the underlying jupnp library omits generics info in this method
                     * signature, we must too when we override it.
                     */
                    @Override
                    @SuppressWarnings("rawtypes")
                    public void failure(
                        final ActionInvocation invocation,
                        final UpnpResponse operation,
                        final String msg) {
                      LOG.warn(
                          "Port forward request for {} {} -> {} failed: {}",
                          portMapping.getProtocol(),
                          portMapping.getInternalPort(),
                          portMapping.getExternalPort(),
                          msg);
                      upnpQueryFuture.completeExceptionally(new Exception(msg));
                    }
                  };

              LOG.info(
                  "Requesting port forward for {} {} -> {}",
                  portMapping.getProtocol(),
                  portMapping.getInternalPort(),
                  portMapping.getExternalPort());

              upnpService.getControlPoint().execute(callback);

              return upnpQueryFuture;
            });
  }

  /**
   * Recursively crawls the given device to look for specific services.
   *
   * @param device is the device to inspect for desired services.
   * @param serviceTypes is a set of service types to look for.
   */
  private void inspectDeviceRecursive(final RemoteDevice device, final Set<String> serviceTypes) {
    for (RemoteService service : device.getServices()) {
      String serviceType = service.getServiceType().getType();
      if (serviceTypes.contains(serviceType)) {
        synchronized (this) {
          // log a warning if we detect a second WANIPConnection service
          RemoteService existingService = recognizedServices.get(serviceType);
          if (null != existingService && service != existingService) {
            LOG.warn(
                "Detected multiple WANIPConnection services on network. This may interfere with NAT circumvention.");
            continue;
          }
          recognizedServices.put(serviceType, service);
        }
      }
    }
    for (RemoteDevice subDevice : device.getEmbeddedDevices()) {
      inspectDeviceRecursive(subDevice, serviceTypes);
    }
  }
}
