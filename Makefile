#-------------------------------------------------------------------------
#
# Makefile for src/interfaces
#
# Copyright (c) 1994, Regents of the University of California
#
# $Header$
#
#-------------------------------------------------------------------------

subdir = src/interfaces/jdbc
top_builddir = ../../..
include $(top_builddir)/src/Makefile.global

all distprep:
	@$(ANT) -buildfile $(top_builddir)/build.xml

install:
	@$(ANT) -Dinstall.directory=$(DESTDIR)$(libdir)/java \
		-buildfile $(top_builddir)/build.xml \
		install

installdirs uninstall dep depend:
	@echo Nothing for JDBC

clean distclean maintainer-clean:
	@$(ANT) -buildfile $(top_builddir)/build.xml clean

