package org.postgresql.core;

import java.io.IOException;

public class PGBindException extends IOException {

	private IOException _ioe;

	public PGBindException(IOException ioe) {
		_ioe = ioe;
	}

	public IOException getIOException() {
		return _ioe;
	}
}
