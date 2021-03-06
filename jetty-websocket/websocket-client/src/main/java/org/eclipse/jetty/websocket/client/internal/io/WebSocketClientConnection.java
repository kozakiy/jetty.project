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

package org.eclipse.jetty.websocket.client.internal.io;

import java.util.concurrent.Executor;

import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.websocket.client.WebSocketClientFactory;
import org.eclipse.jetty.websocket.client.internal.DefaultWebSocketClient;
import org.eclipse.jetty.websocket.client.masks.Masker;
import org.eclipse.jetty.websocket.core.io.AbstractWebSocketConnection;
import org.eclipse.jetty.websocket.core.protocol.WebSocketFrame;

/**
 * Client side WebSocket physical connection.
 */
public class WebSocketClientConnection extends AbstractWebSocketConnection
{
    private final WebSocketClientFactory factory;
    private final DefaultWebSocketClient client;
    private final Masker masker;
    private boolean connected;

    public WebSocketClientConnection(EndPoint endp, Executor executor, DefaultWebSocketClient client)
    {
        super(endp,executor,client.getFactory().getScheduler(),client.getPolicy(),client.getFactory().getBufferPool());
        this.client = client;
        this.factory = client.getFactory();
        this.connected = false;
        this.masker = client.getMasker();
    }

    public DefaultWebSocketClient getClient()
    {
        return client;
    }

    @Override
    public void onClose()
    {
        super.onClose();
        factory.sessionClosed(getSession());
    };

    @Override
    public void onOpen()
    {
        if (!connected)
        {
            factory.sessionOpened(getSession());
            connected = true;
        }
        super.onOpen();
    }

    @Override
    public <C> void output(C context, Callback<C> callback, WebSocketFrame frame)
    {
        masker.setMask(frame);
        super.output(context,callback,frame);
    }
}
