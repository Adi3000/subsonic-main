/*
 This file is part of Subsonic.

 Subsonic is free software: you can redistribute it and/or modify
 it under the terms of the GNU General Public License as published by
 the Free Software Foundation, either version 3 of the License, or
 (at your option) any later version.

 Subsonic is distributed in the hope that it will be useful,
 but WITHOUT ANY WARRANTY; without even the implied warranty of
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 GNU General Public License for more details.

 You should have received a copy of the GNU General Public License
 along with Subsonic.  If not, see <http://www.gnu.org/licenses/>.

 Copyright 2009 (C) Sindre Mehus
 */
package net.sourceforge.subsonic.domain;

import java.util.Date;

import net.sourceforge.subsonic.dao.AbstractDataObject;

/**
 * An icon representing a user.
 *
 * @author Sindre Mehus
 */
public class Avatar extends AbstractDataObject {

    private int id;
    private String name;
    private Date createdDate;
    private String mimeType;
    private int width;
    private int height;
    private byte[] data;

    public Avatar(int id, String name, Date createdDate, String mimeType, int width, int height, byte[] data) {
        this.id = id;
        this.name = name;
        this.createdDate = createdDate;
        this.mimeType = mimeType;
        this.width = width;
        this.height = height;
        this.data = data;
    }

    public Integer getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public Date getCreatedDate() {
        return createdDate;
    }

    public String getMimeType() {
        return mimeType;
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public byte[] getData() {
        return data;
    }
}
