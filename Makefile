#-------------------------------------------------------------------------
#
# Makefile for JDBC driver
#
# Copyright (c) 2001, PostgreSQL Global Development Group
#
# $Header: /home/davec/jdbc/CVSROOT/Attic/Makefile,v 1.33 2001/07/06 23:07:20 petere Exp $
#
#-------------------------------------------------------------------------

subdir = src/interfaces/jdbc
top_builddir = ../../..
include $(top_builddir)/src/Makefile.global

majorversion := $(shell echo $(VERSION) | sed 's/^\([0-9][0-9]*\)\..*$$/\1/')
minorversion := $(shell echo $(VERSION) | sed 's/^[0-9][0-9]*\.\([0-9][0-9]*\).*$$/\1/')

properties := -Dmajor=$(majorversion) -Dminor=$(minorversion) \
		-Dfullversion=$(VERSION) \
		-Ddef_pgport=$(DEF_PGPORT)

all:
	$(ANT) -buildfile $(srcdir)/build.xml all \
	  $(properties)

install: installdirs
	$(ANT) -buildfile $(srcdir)/build.xml install \
	  -Dinstall.directory=$(javadir) $(properties)

installdirs:
	$(mkinstalldirs) $(javadir)

uninstall:
	$(ANT) -buildfile $(srcdir)/build.xml uninstall \
	  -Dinstall.directory=$(javadir)

clean distclean maintainer-clean:
	$(ANT) -buildfile $(srcdir)/build.xml clean

check:
	$(ANT) -buildfile $(srcdir)/build.xml test
