//
//  ========================================================================
//  Copyright (c) 1995-2012 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.client;

import java.io.IOException;
import java.nio.channels.AsynchronousCloseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jetty.client.api.Connection;
import org.eclipse.jetty.client.api.Destination;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.client.api.Result;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.FutureCallback;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.component.Dumpable;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

public class HttpDestination implements Destination, AutoCloseable, Dumpable
{
    private static final Logger LOG = Log.getLogger(HttpDestination.class);

    private final AtomicInteger connectionCount = new AtomicInteger();
    private final HttpClient client;
    private final String scheme;
    private final String host;
    private final int port;
    private final Queue<RequestContext> requests;
    private final BlockingQueue<Connection> idleConnections;
    private final BlockingQueue<Connection> activeConnections;
    private final RequestNotifier requestNotifier;
    private final ResponseNotifier responseNotifier;

    public HttpDestination(HttpClient client, String scheme, String host, int port)
    {
        this.client = client;
        this.scheme = scheme;
        this.host = host;
        this.port = port;
        this.requests = new ArrayBlockingQueue<>(client.getMaxQueueSizePerAddress());
        this.idleConnections = new ArrayBlockingQueue<>(client.getMaxConnectionsPerAddress());
        this.activeConnections = new ArrayBlockingQueue<>(client.getMaxConnectionsPerAddress());
        this.requestNotifier = new RequestNotifier(client);
        this.responseNotifier = new ResponseNotifier(client);
    }

    protected BlockingQueue<Connection> getIdleConnections()
    {
        return idleConnections;
    }

    protected BlockingQueue<Connection> getActiveConnections()
    {
        return activeConnections;
    }

    @Override
    public String getScheme()
    {
        return scheme;
    }

    @Override
    public String getHost()
    {
        return host;
    }

    @Override
    public int getPort()
    {
        return port;
    }

    public void send(Request request, List<Response.ResponseListener> listeners)
    {
        if (!scheme.equals(request.getScheme()))
            throw new IllegalArgumentException("Invalid request scheme " + request.getScheme() + " for destination " + this);
        if (!host.equals(request.getHost()))
            throw new IllegalArgumentException("Invalid request host " + request.getHost() + " for destination " + this);
        int port = request.getPort();
        if (port >= 0 && this.port != port)
            throw new IllegalArgumentException("Invalid request port " + port + " for destination " + this);

        RequestContext requestContext = new RequestContext(request, listeners);
        if (client.isRunning())
        {
            if (requests.offer(requestContext))
            {
                if (!client.isRunning() && requests.remove(requestContext))
                {
                    throw new RejectedExecutionException(client + " is stopping");
                }
                else
                {
                    LOG.debug("Queued {}", request);
                    requestNotifier.notifyQueued(request);
                    Connection connection = acquire();
                    if (connection != null)
                        process(connection, false);
                }
            }
            else
            {
                throw new RejectedExecutionException("Max requests per address " + client.getMaxQueueSizePerAddress() + " exceeded");
            }
        }
        else
        {
            throw new RejectedExecutionException(client + " is stopped");
        }
    }

    public Future<Connection> newConnection()
    {
        FutureCallback<Connection> result = new FutureCallback<>();
        newConnection(result);
        return result;
    }

    protected void newConnection(Callback<Connection> callback)
    {
        client.newConnection(this, callback);
    }

    protected Connection acquire()
    {
        Connection result = idleConnections.poll();
        if (result != null)
            return result;

        final int maxConnections = client.getMaxConnectionsPerAddress();
        while (true)
        {
            int current = connectionCount.get();
            final int next = current + 1;

            if (next > maxConnections)
            {
                LOG.debug("Max connections {} reached for {}", current, this);
                // Try again the idle connections
                return idleConnections.poll();
            }

            if (connectionCount.compareAndSet(current, next))
            {
                LOG.debug("Creating connection {}/{} for {}", next, maxConnections, this);
                newConnection(new Callback<Connection>()
                {
                    @Override
                    public void completed(Connection connection)
                    {
                        LOG.debug("Created connection {}/{} {} for {}", next, maxConnections, connection, HttpDestination.this);
                        process(connection, true);
                    }

                    @Override
                    public void failed(Connection connection, final Throwable x)
                    {
                        LOG.debug("Connection failed {} for {}", x, HttpDestination.this);
                        connectionCount.decrementAndGet();
                        client.getExecutor().execute(new Runnable()
                        {
                            @Override
                            public void run()
                            {
                                drain(x);
                            }
                        });
                    }
                });
                // Try again the idle connections
                return idleConnections.poll();
            }
        }
    }

    private void drain(Throwable x)
    {
        RequestContext requestContext;
        while ((requestContext = requests.poll()) != null)
        {
            Request request = requestContext.request;
            requestNotifier.notifyFailure(request, x);
            List<Response.ResponseListener> listeners = requestContext.listeners;
            HttpResponse response = new HttpResponse(request, listeners);
            responseNotifier.notifyFailure(listeners, response, x);
            responseNotifier.notifyComplete(listeners, new Result(request, x, response, x));
        }
    }

    /**
     * <p>Processes a new connection making it idle or active depending on whether requests are waiting to be sent.</p>
     * <p>A new connection is created when a request needs to be executed; it is possible that the request that
     * triggered the request creation is executed by another connection that was just released, so the new connection
     * may become idle.</p>
     * <p>If a request is waiting to be executed, it will be dequeued and executed by the new connection.</p>
     *
     * @param connection the new connection
     */
    protected void process(Connection connection, boolean dispatch)
    {
        // Ugly cast, but lack of generic reification forces it
        final HttpConnection httpConnection = (HttpConnection)connection;

        RequestContext requestContext = requests.poll();
        if (requestContext == null)
        {
            LOG.debug("{} idle", httpConnection);
            if (!idleConnections.offer(httpConnection))
            {
                LOG.debug("{} idle overflow");
                httpConnection.close();
            }
            if (!client.isRunning())
            {
                LOG.debug("{} is stopping", client);
                remove(httpConnection);
                httpConnection.close();
            }
        }
        else
        {
            final Request request = requestContext.request;
            final List<Response.ResponseListener> listeners = requestContext.listeners;
            if (request.aborted())
            {
                abort(request, listeners, "Aborted");
                LOG.debug("Aborted {} before processing", request);
            }
            else
            {
                LOG.debug("{} active", httpConnection);
                if (!activeConnections.offer(httpConnection))
                {
                    LOG.warn("{} active overflow");
                }
                if (dispatch)
                {
                    client.getExecutor().execute(new Runnable()
                    {
                        @Override
                        public void run()
                        {
                            httpConnection.send(request, listeners);
                        }
                    });
                }
                else
                {
                    httpConnection.send(request, listeners);
                }
            }
        }
    }

    public void release(Connection connection)
    {
        LOG.debug("{} released", connection);
        if (client.isRunning())
        {
            boolean removed = activeConnections.remove(connection);
            if (removed)
                process(connection, false);
            else
                LOG.debug("{} explicit", connection);
        }
        else
        {
            LOG.debug("{} is stopped", client);
            remove(connection);
            connection.close();
        }
    }

    public void remove(Connection connection)
    {
        LOG.debug("{} removed", connection);
        connectionCount.decrementAndGet();
        activeConnections.remove(connection);
        idleConnections.remove(connection);

        // We need to execute queued requests even if this connection failed.
        // We may create a connection that is not needed, but it will eventually
        // idle timeout, so no worries
        if (!requests.isEmpty())
        {
            connection = acquire();
            if (connection != null)
                process(connection, false);
        }
    }

    public void close()
    {
        for (Connection connection : idleConnections)
            connection.close();
        idleConnections.clear();

        // A bit drastic, but we cannot wait for all requests to complete
        for (Connection connection : activeConnections)
            connection.close();
        activeConnections.clear();

        drain(new AsynchronousCloseException());

        connectionCount.set(0);

        LOG.debug("Closed {}", this);
    }

    public boolean abort(Request request, String reason)
    {
        for (RequestContext requestContext : requests)
        {
            if (requestContext.request == request)
            {
                if (requests.remove(requestContext))
                {
                    // We were able to remove the pair, so it won't be processed
                    abort(request, requestContext.listeners, reason);
                    LOG.debug("Aborted {} while queued", request);
                    return true;
                }
            }
        }
        return false;
    }

    private void abort(Request request, List<Response.ResponseListener> listeners, String reason)
    {
        HttpResponse response = new HttpResponse(request, listeners);
        HttpResponseException responseFailure = new HttpResponseException(reason, response);
        responseNotifier.notifyFailure(listeners, response, responseFailure);
        HttpRequestException requestFailure = new HttpRequestException(reason, request);
        responseNotifier.notifyComplete(listeners, new Result(request, requestFailure, response, responseFailure));
    }

    @Override
    public String dump()
    {
        return ContainerLifeCycle.dump(this);
    }

    @Override
    public void dump(Appendable out, String indent) throws IOException
    {
        ContainerLifeCycle.dumpObject(out, this + " - requests queued: " + requests.size());
        List<String> connections = new ArrayList<>();
        for (Connection connection : idleConnections)
            connections.add(connection + " - IDLE");
        for (Connection connection : activeConnections)
            connections.add(connection + " - ACTIVE");
        ContainerLifeCycle.dump(out, indent, connections);
    }

    @Override
    public String toString()
    {
        return String.format("%s(%s://%s:%d)", HttpDestination.class.getSimpleName(), getScheme(), getHost(), getPort());
    }

    private static class RequestContext
    {
        private final Request request;
        private final List<Response.ResponseListener> listeners;

        private RequestContext(Request request, List<Response.ResponseListener> listeners)
        {
            this.request = request;
            this.listeners = listeners;
        }
    }
}
