/*
 * Copyright 2009 Cedric Priscal
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android_serialport_api;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Serial port from the GC039 printer SDK (SerialPrinterQuck_hand 5.6.1), trimmed to what the app
 * uses. The package, class name, the {@code mFd} field and the native methods must stay exactly
 * as they are: libserial_port.so binds to them by name.
 */
public class SerialPort {

    /* Do not remove or rename the field mFd: it is used by native method close(); */
    private FileDescriptor mFd;
    private final FileInputStream mFileInputStream;
    private final FileOutputStream mFileOutputStream;

    public SerialPort(File device, int baudrate, int flags) throws SecurityException, IOException {
        if (!device.canRead() || !device.canWrite()) {
            // Same as the SDK: try to open up the port, then give up if it is still closed.
            try {
                Runtime.getRuntime().exec("chmod 666 " + device.getAbsolutePath()).waitFor();
            } catch (Exception ignored) {
            }
            if (!device.canRead() || !device.canWrite()) {
                throw new SecurityException("No read/write access to " + device.getAbsolutePath());
            }
        }
        mFd = open(device.getAbsolutePath(), baudrate, flags);
        if (mFd == null) {
            throw new IOException("Could not open " + device.getAbsolutePath());
        }
        mFileInputStream = new FileInputStream(mFd);
        mFileOutputStream = new FileOutputStream(mFd);
    }

    public InputStream getInputStream() {
        return mFileInputStream;
    }

    public OutputStream getOutputStream() {
        return mFileOutputStream;
    }

    private native static FileDescriptor open(String path, int baudrate, int flags);

    public native void close();

    static {
        System.loadLibrary("serial_port");
    }
}
