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
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jupnp.DefaultUpnpServiceConfiguration;
import org.jupnp.UpnpService;
import org.jupnp.UpnpServiceImpl;
import org.jupnp.model.action.ActionInvocation;
import org.jupnp.model.message.UpnpResponse;
import org.jupnp.model.message.header.STAllHeader;
import org.jupnp.model.meta.LocalDevice;
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

public class UpnpNatManager {
  protected static final Logger LOG = LogManager.getLogger();

  public static final String SERVICE_DEFAULT_NAMESPACE = "schemas-upnp-org";
  public static final String SERVICE_TYPE_WAN_IP_CONNECTION = "WANIPConnection";

  boolean started = false;
  UpnpService upnpService = null;
  RegistryListener registryListener = null;

  Map<String, RemoteService> recognizedServices;
  String discoveredOnLocalAddress = null;

  /** Empty constructor. Creates in instance of UpnpServiceImpl. */
  public UpnpNatManager() {
    // this(new UpnpServiceImpl(new DefaultUpnpServiceConfiguration()));

    // Workaround for an issue in the jupnp library: the ExecutorService used misconfigures
    // its ThreadPoolExecutor, causing it to only launch a single thread. This prevents any work
    // from getting done (effectively a deadlock). The issue is fixed here:
    //   https://github.com/jupnp/jupnp/pull/117
    // However, this fix has not made it into any releases yet.
    // TODO: once a new release is available, remove this @Override
    this(
        new UpnpServiceImpl(
            new DefaultUpnpServiceConfiguration() {
              @Override
              protected ExecutorService createDefaultExecutorService() {
                ThreadPoolExecutor threadPoolExecutor =
                    new ThreadPoolExecutor(
                        16,
                        200,
                        10,
                        TimeUnit.SECONDS,
                        new ArrayBlockingQueue<Runnable>(2000),
                        new JUPnPThreadFactory(),
                        new ThreadPoolExecutor.DiscardPolicy() {
                          // The pool is bounded and rejections will happen during shutdown
                          @Override
                          public void rejectedExecution(
                              final Runnable runnable,
                              final ThreadPoolExecutor threadPoolExecutor) {
                            // Log and discard
                            LOG.warn("Thread pool rejected execution of " + runnable.getClass());
                            super.rejectedExecution(runnable, threadPoolExecutor);
                          }
                        });
                threadPoolExecutor.allowCoreThreadTimeOut(true);
                return threadPoolExecutor;
              }
            }));
  }

  /**
   * Constructor
   *
   * @param service is the desired instance of UpnpService.
   */
  public UpnpNatManager(final UpnpService service) {
    upnpService = service;

    // registry listener to observe new devices and look for specific services
    registryListener =
        new RegistryListener() {

          @Override
          public void remoteDeviceDiscoveryStarted(
              final Registry registry, final RemoteDevice device) {}

          @Override
          public void remoteDeviceDiscoveryFailed(
              final Registry registry, final RemoteDevice device, final Exception ex) {}

          @Override
          public void remoteDeviceAdded(final Registry registry, final RemoteDevice device) {
            LOG.debug("UPnP Device discovered: " + device.getDetails().getFriendlyName());
            inspectDeviceRecursive(device, recognizedServices.keySet());
          }

          @Override
          public void remoteDeviceUpdated(final Registry registry, final RemoteDevice device) {}

          @Override
          public void remoteDeviceRemoved(final Registry registry, final RemoteDevice device) {}

          @Override
          public void localDeviceAdded(final Registry registry, final LocalDevice device) {}

          @Override
          public void localDeviceRemoved(final Registry registry, final LocalDevice device) {}

          @Override
          public void beforeShutdown(final Registry registry) {}

          @Override
          public void afterShutdown() {}
        };

    // prime our recognizedServices map so we can use its key-set later
    recognizedServices = new HashMap<>();
    recognizedServices.put(SERVICE_TYPE_WAN_IP_CONNECTION, null);
  }

  /**
   * Start the manager. Must not be in started state.
   *
   * @throws IllegalStateException if already started.
   */
  public void start() {
    if (started) {
      throw new IllegalStateException("Cannot start already-started service");
    }

    LOG.info("starting upnp service...");
    upnpService.startup();
    upnpService.getRegistry().addListener(registryListener);

    // TODO: does jupnp do this automatically?
    upnpService.getControlPoint().search(new STAllHeader());

    started = true;
  }

  /**
   * Stop the manager. Must not be in stopped state.
   *
   * @throws IllegalStateException if stopped.
   */
  public void stop() {
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
  public RemoteService getService(final String type) {
    return recognizedServices.get(type);
  }

  /**
   * Get the discovered WANIPConnection service, if any.
   *
   * @return the WANIPConnection Service if we have found it, or null.
   */
  public RemoteService getWANIPConnectionService() {
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
  public CompletableFuture<RemoteService> discoverService(final String serviceType) {

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
  public CompletableFuture<String> queryExternalIPAddress() {

    CompletableFuture<String> upnpQueryFuture = new CompletableFuture<>();

    return discoverService(SERVICE_TYPE_WAN_IP_CONNECTION)
        .thenCompose(
            service -> {

              // our query, which will be handled asynchronously by the jupnp library
              GetExternalIP callback =
                  new GetExternalIP(service) {

                    /**
                     * Override the success(ActionInvocation) version of success so that we can take
                     * a peek at the network interface that we discovered this on.
                     *
                     * <p>Because the underlying jupnp library omits generics info in this method
                     * signature, we must too when we override it.
                     */
                    @Override
                    @SuppressWarnings("rawtypes")
                    public void success(final ActionInvocation invocation) {
                      RemoteService service = (RemoteService) invocation.getAction().getService();
                      RemoteDevice device = service.getDevice();
                      RemoteDeviceIdentity identity = device.getIdentity();

                      discoveredOnLocalAddress =
                          identity.getDiscoveredOnLocalAddress().getHostAddress();

                      super.success(invocation);
                    }

                    @Override
                    protected void success(final String result) {
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
  public CompletableFuture<String> requestPortForward(
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
  public CompletableFuture<String> requestPortForward(final PortMapping portMapping) {

    CompletableFuture<String> upnpQueryFuture = new CompletableFuture<>();

    return discoverService(SERVICE_TYPE_WAN_IP_CONNECTION)
        .thenCompose(
            service -> {

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
                      // TODO: return value here?
                      upnpQueryFuture.complete("TODO");
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
   * Recursively crawls the given device to look for specific services.
   *
   * @param device is the device to inspect for desired services.
   * @param serviceTypes is a set of service types to look for.
   */
  protected void inspectDeviceRecursive(final RemoteDevice device, final Set<String> serviceTypes) {
    for (RemoteService service : device.getServices()) {
      String serviceType = service.getServiceType().getType();
      if (serviceTypes.contains(serviceType)) {
        // TODO: handle case where service is already "recognized" as this could lead to
        // some odd bugs
        recognizedServices.put(serviceType, service);
      }
    }
    for (RemoteDevice subDevice : device.getEmbeddedDevices()) {
      inspectDeviceRecursive(subDevice, serviceTypes);
    }
  }
}
