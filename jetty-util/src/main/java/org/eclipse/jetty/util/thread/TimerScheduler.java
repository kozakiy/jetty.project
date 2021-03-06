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

package org.eclipse.jetty.util.thread;

import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.util.component.AbstractLifeCycle;

public class TimerScheduler extends AbstractLifeCycle implements Scheduler
{
    /*
     * This class uses the Timer class rather than an ScheduledExecutionService because
     * it uses the same algorithm internally and the signature is cheaper to use as there are no
     * Futures involved (which we do not need).
     * However, Timer is still locking and a concurrent queue would be better.
     */

    private final String _name;
    private Timer _timer;

    public TimerScheduler()
    {
        this(null);
    }

    public TimerScheduler(String name)
    {
        _name=name;
    }

    @Override
    protected void doStart() throws Exception
    {
        _timer=_name==null?new Timer():new Timer(_name);
        super.doStart();
    }

    @Override
    protected void doStop() throws Exception
    {
        _timer.cancel();
        super.doStop();
        _timer=null;
    }

    @Override
    public Task schedule(final Runnable task, final long delay, final TimeUnit units)
    {
        Timer timer=_timer;
        if (timer==null)
            throw new RejectedExecutionException("STOPPED: "+this);
       SimpleTask t = new SimpleTask(task);
       timer.schedule(t,units.toMillis(delay));
       return t;
    }

    private static class SimpleTask extends TimerTask implements Task
    {
        private final Runnable _task;

        private SimpleTask(Runnable runnable)
        {
            _task=runnable;
        }

        @Override
        public void run()
        {
            _task.run();
        }

        @Override
        public String toString()
        {
            return String.format("%s.%s@%x",
                    TimerScheduler.class.getSimpleName(),
                    SimpleTask.class.getSimpleName(),
                    hashCode());
        }
    }
}
