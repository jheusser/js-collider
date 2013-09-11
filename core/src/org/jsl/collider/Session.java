/*
 * JS-Collider framework.
 * Copyright (C) 2013 Sergey Zubarev
 * info@js-labs.org
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

import java.net.SocketAddress;
import java.nio.ByteBuffer;

public interface Session
{
    public interface Listener
    {
        public abstract void onDataReceived( ByteBuffer data );
        public abstract void onConnectionClosed();
    }

    public Collider getCollider();
    public SocketAddress getLocalAddress();
    public SocketAddress getRemoteAddress();

    public boolean sendData( ByteBuffer data );
    public boolean sendDataSync( ByteBuffer data );
    public boolean closeConnection();
}
