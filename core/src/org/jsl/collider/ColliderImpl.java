/*
 * Copyright (C) 2013 Sergey Zubarev, info@js-labs.org
 *
 * This file is a part of JS-Collider framework.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.jsl.collider;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Set;
import java.util.Map;
import java.util.HashMap;
import java.util.Iterator;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;


public class ColliderImpl extends Collider
{
    public interface ChannelHandler
    {
        public int handleReadyOps( ThreadPool threadPool );
    }

    public static abstract class SelectorThreadRunnable
    {
        public volatile SelectorThreadRunnable nextSelectorThreadRunnable;
        abstract public int runInSelectorThread();
    }

    private class Stopper1 extends ThreadPool.Runnable
    {
        public void runInThreadPool()
        {
            SessionEmitter [] emitters = null;
            m_lock.lock();
            try
            {
                int size = m_emitters.size();
                if (size > 0)
                {
                    emitters = new SessionEmitter[size];
                    Iterator<SessionEmitter> it = m_emitters.keySet().iterator();
                    for (int idx=0; idx<size; idx++)
                        emitters[idx] = it.next();
                }
            }
            finally
            {
                m_lock.unlock();
            }

            if (emitters != null)
            {
                boolean interrupted = false;
                try
                {
                    for (SessionEmitter emitter : emitters)
                    {
                        try
                        {
                            removeEmitter( emitter );
                        }
                        catch (InterruptedException ex)
                        {
                            if (s_logger.isLoggable(Level.WARNING))
                                s_logger.warning( ex.toString() );
                            interrupted = true;
                        }
                    }
                }
                finally
                {
                    if (interrupted)
                        Thread.currentThread().interrupt();
                }
            }

            executeInSelectorThread( new Stopper2() );
        }
    }

    private class Stopper2 extends SelectorThreadRunnable
    {
        public int runInSelectorThread()
        {
            Set<SelectionKey> keys = m_selector.keys();
            for (SelectionKey key : keys)
            {
                Object attachment = key.attachment();
                if (attachment instanceof SessionImpl)
                    ((SessionImpl)attachment).closeConnection();
                /*
                 * else if (attachment instanceof AcceptorImpl)
                 * {
                 *     Can happen, canceled SelectionKey is not removed from the
                 *     Selector right at the SelectionKey.cancel() call,
                 *     but will present in the keys set till the next
                 *     Selector.select() call.
                 * }
                 */
            }
            m_state = ST_STOPPING;
            return 0;
        }
    }

    private static class DummyRunnable extends SelectorThreadRunnable
    {
        public int runInSelectorThread()
        {
            return 0;
        }
    }

    private class SelectorAlarm extends ThreadPool.Runnable
    {
        public SelectorThreadRunnable cmp;

        public SelectorAlarm( SelectorThreadRunnable runnable )
        {
            cmp = runnable;
        }

        public void runInThreadPool()
        {
            if (m_strList.get(15) == cmp)
                m_selector.wakeup();
            cmp = null;
            m_alarm.compareAndSet( null, this );
        }
    }

    private DataBlockCache getInputQueueDataBlockCache( final SessionEmitter sessionEmitter )
    {
        final Config config = getConfig();

        int inputQueueBlockSize = sessionEmitter.inputQueueBlockSize;
        if (inputQueueBlockSize == 0)
        {
            inputQueueBlockSize = config.inputQueueBlockSize;
            assert( inputQueueBlockSize > 0 );
        }

        DataBlockCache cache = null;

        m_lock.lock();
        try
        {
            cache = m_dataBlockCache.get( inputQueueBlockSize );
            cache = m_dataBlockCache.get( inputQueueBlockSize );
            if (cache == null)
            {
                cache = new DataBlockCache(
                            config.useDirectBuffers,
                            inputQueueBlockSize,
                            config.inputQueueCacheInitialSize,
                            config.inputQueueCacheMaxSize );
                m_dataBlockCache.put( inputQueueBlockSize, cache );
            }
        }
        finally
        {
            m_lock.unlock();
        }

        return cache;
    }

    private void removeEmitter( SessionEmitter sessionEmitter ) throws InterruptedException
    {
        SessionEmitterImpl emitterImpl;
        m_lock.lock();
        try
        {
            emitterImpl = m_emitters.get( sessionEmitter );
            if (emitterImpl == null)
                return;
            /*
             * m_emitters.remove( sessionEmitter );
             * It is better to keep the emitter in the container
             * allowing collider stop properly later.
             */
        }
        finally
        {
            m_lock.unlock();
        }
        emitterImpl.stopAndWait();
    }

    public void removeEmitterNoWait( SessionEmitter sessionEmitter )
    {
        /* Supposed to be called by emitter itself. */
        m_lock.lock();
        try
        {
            m_emitters.remove( sessionEmitter );
        }
        finally
        {
            m_lock.unlock();
        }
    }

    private final static int ST_RUNNING = 1;
    private final static int ST_STOPPING = 2;

    private static final Logger s_logger = Logger.getLogger( "org.jsl.collider.Collider" );

    private final Selector m_selector;
    private final ThreadPool m_threadPool;
    private int m_state;

    private final ReentrantLock m_lock;
    private final Map<SessionEmitter, SessionEmitterImpl> m_emitters;
    private final Map<Integer, DataBlockCache> m_dataBlockCache;
    private boolean m_stop;

    private final AtomicReferenceArray<SelectorThreadRunnable> m_strList;
    private SelectorThreadRunnable m_strLater;
    private final AtomicReference<SelectorAlarm> m_alarm;

    public ColliderImpl( Config config ) throws IOException
    {
        super( config );

        m_selector = Selector.open();

        int threadPoolThreads = config.threadPoolThreads;
        if (threadPoolThreads == 0)
            threadPoolThreads = Runtime.getRuntime().availableProcessors();
        if (threadPoolThreads < 4)
            threadPoolThreads = 4;
        m_threadPool = new ThreadPool( "CTP", threadPoolThreads );

        if (config.inputQueueCacheMaxSize == 0)
            config.inputQueueCacheMaxSize = (threadPoolThreads * 3);

        m_state = ST_RUNNING;

        m_lock = new ReentrantLock();
        m_emitters = new HashMap<SessionEmitter, SessionEmitterImpl>();
        m_dataBlockCache = new HashMap<Integer, DataBlockCache>();
        m_stop = false;

        m_strList = new AtomicReferenceArray<SelectorThreadRunnable>( 16+16+15 );
        m_alarm = new AtomicReference<SelectorAlarm>( new SelectorAlarm(null) );
    }

    public void run()
    {
        if (s_logger.isLoggable(Level.FINE))
            s_logger.fine( "start" );

        m_threadPool.start();

        final DummyRunnable dummyRunnable = new DummyRunnable();
        int statLoopIt = 0;
        int statLoopReadersG0 = 0;
        int readers = 0;

        try
        {
            for (;;)
            {
                statLoopIt++;
                if (m_state != ST_STOPPING)
                {
                    if (readers > 0)
                    {
                        statLoopReadersG0++;
                        m_selector.selectNow();
                    }
                    else
                        m_selector.select();
                }
                else
                {
                    if (m_selector.keys().size() == 0)
                    {
                        assert( readers == 0 );
                        break;
                    }
                    else
                        m_selector.selectNow();
                }

                if (m_strList.compareAndSet(31, null, dummyRunnable))
                    m_strList.set( 15, dummyRunnable );

                Set<SelectionKey> selectedKeys = m_selector.selectedKeys();
                for (SelectionKey key : selectedKeys)
                {
                    ChannelHandler channelHandler = (ChannelHandler) key.attachment();
                    readers += channelHandler.handleReadyOps( m_threadPool );
                }
                selectedKeys.clear();

                SelectorThreadRunnable runnable;
                do { runnable = m_strList.get( 15 ); }
                while (runnable == null);

                for (;;)
                {
                    SelectorThreadRunnable next = runnable.nextSelectorThreadRunnable;
                    if (next == null)
                    {
                        m_strList.set( 15, null );
                        if (!m_strList.compareAndSet(31, runnable, null))
                        {
                            while (runnable.nextSelectorThreadRunnable == null);
                            next = runnable.nextSelectorThreadRunnable;
                            runnable.nextSelectorThreadRunnable = null;
                        }
                    }
                    else
                        runnable.nextSelectorThreadRunnable = null;

                    readers -= runnable.runInSelectorThread();

                    runnable = next;
                    if (runnable == null)
                    {
                        runnable = m_strList.get( 15 );
                        if (runnable == null)
                            break;
                    }
                }

                SelectorThreadRunnable strLater = m_strLater;
                m_strLater = null;
                while (strLater != null)
                {
                    runnable = strLater;
                    strLater = runnable.nextSelectorThreadRunnable;
                    runnable.nextSelectorThreadRunnable = null;
                    final int rc = runnable.runInSelectorThread();
                    assert( rc == 0 );
                }

                /* End of select loop */
            }

            m_threadPool.stopAndWait();
        }
        catch (IOException ex)
        {
            if (s_logger.isLoggable(Level.WARNING))
                s_logger.warning( ex.toString() );
        }
        catch (InterruptedException ex)
        {
            if (s_logger.isLoggable(Level.WARNING))
                s_logger.warning( ex.toString() );
        }

        for (Map.Entry<Integer, DataBlockCache> me : m_dataBlockCache.entrySet())
            me.getValue().clear( s_logger );
        m_dataBlockCache.clear();

        if (s_logger.isLoggable(Level.FINE))
            s_logger.fine( "finish (" + statLoopIt + ", " + statLoopReadersG0 + ")." );
    }

    public void stop()
    {
        if (s_logger.isLoggable(Level.FINE))
            s_logger.fine( "" );

        m_lock.lock();
        try
        {
            if (m_stop)
                return;
            m_stop = true;
        }
        finally
        {
            m_lock.unlock();
        }

        executeInThreadPool( new Stopper1() );
    }

    public final void executeInSelectorThread( SelectorThreadRunnable runnable )
    {
        assert( runnable.nextSelectorThreadRunnable == null );
        SelectorThreadRunnable tail = m_strList.getAndSet( 31, runnable );
        if (tail == null)
        {
            m_strList.set( 15, runnable );

            for (;;)
            {
                SelectorAlarm alarm = m_alarm.get();
                if (alarm == null)
                {
                    m_threadPool.execute( new SelectorAlarm(runnable) );
                    break;
                }
                else if (m_alarm.compareAndSet(alarm, null))
                {
                    alarm.cmp = runnable;
                    m_threadPool.execute( alarm );
                    break;
                }
            }
        }
        else
            tail.nextSelectorThreadRunnable = runnable;
    }

    public final void executeInSelectorThreadNoWakeup( SelectorThreadRunnable runnable )
    {
        assert( runnable.nextSelectorThreadRunnable == null );
        SelectorThreadRunnable tail = m_strList.getAndSet( 31, runnable );
        if (tail == null)
            m_strList.set( 15, runnable );
        else
            tail.nextSelectorThreadRunnable = runnable;
    }

    public final void executeInSelectorThreadLater( SelectorThreadRunnable runnable )
    {
        assert( runnable.nextSelectorThreadRunnable == null );
        runnable.nextSelectorThreadRunnable = m_strLater;
        m_strLater = runnable;
    }

    public final void executeInThreadPool( ThreadPool.Runnable runnable )
    {
        m_threadPool.execute( runnable );
    }

    public void addAcceptor( Acceptor acceptor )
    {
        ServerSocketChannel channel;
        try
        {
            channel = ServerSocketChannel.open();
            channel.configureBlocking( false );

            final ServerSocket socket = channel.socket();
            socket.setReuseAddress( acceptor.reuseAddr );
            socket.bind( acceptor.getAddr() );
        }
        catch (IOException ex)
        {
            acceptor.onException(ex);
            return;
        }

        DataBlockCache inputQueueDataBlockCache = getInputQueueDataBlockCache( acceptor );

        AcceptorImpl acceptorImpl = new AcceptorImpl(
                this,
                inputQueueDataBlockCache,
                acceptor,
                m_selector,
                channel );

        IOException ex = null;

        m_lock.lock();
        try
        {
            if (m_stop)
                ex = new IOException( "Collider stopped." );
            else if (m_emitters.containsKey(acceptor))
                ex = new IOException( "Acceptor already registered." );
            else
                m_emitters.put( acceptor, acceptorImpl );
        }
        finally
        {
            m_lock.unlock();
        }

        if (ex == null)
            acceptorImpl.start();
        else
        {
            acceptor.onException(ex);
            try
            {
                channel.close();
            }
            catch (IOException ex1)
            {
                /* Should never happen */
                if (s_logger.isLoggable(Level.WARNING))
                    s_logger.warning( ex1.toString() );
            }
        }
    }

    public void removeAcceptor( Acceptor acceptor ) throws InterruptedException
    {
        removeEmitter( acceptor );
    }

    public void addConnector( Connector connector )
    {
        SocketChannel socketChannel;
        boolean connected;

        try
        {
            socketChannel = SocketChannel.open();
            socketChannel.configureBlocking( false );
            connected = socketChannel.connect( connector.getAddr() );
        }
        catch (IOException ex)
        {
            if (s_logger.isLoggable(Level.WARNING))
                s_logger.warning( ex.toString() );

            connector.onException( ex );
            return;
        }

        DataBlockCache inputQueueDataBlockCache = getInputQueueDataBlockCache( connector );

        ConnectorImpl connectorImpl = new ConnectorImpl(
                this,
                inputQueueDataBlockCache,
                connector,
                m_selector );

        IOException ex = null;

        m_lock.lock();
        try
        {
            if (m_stop)
                ex = new IOException( "Collider stopped." );
            else if (m_emitters.containsKey(connector))
                ex = new IOException( "Connector already registered." );
            else
                m_emitters.put( connector, connectorImpl );
        }
        finally
        {
            m_lock.unlock();
        }

        if (ex == null)
            connectorImpl.start( socketChannel, connected );
        else
            connector.onException( ex );
    }

    public void removeConnector( Connector connector ) throws InterruptedException
    {
        removeEmitter( connector );
    }
}
