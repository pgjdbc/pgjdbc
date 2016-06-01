# Copyright (c) 2000-2005, JPackage Project
# All rights reserved.
#
# Redistribution and use in source and binary forms, with or without
# modification, are permitted provided that the following conditions
# are met:
#
# 1. Redistributions of source code must retain the above copyright
#    notice, this list of conditions and the following disclaimer.
# 2. Redistributions in binary form must reproduce the above copyright
#    notice, this list of conditions and the following disclaimer in the
#    documentation and/or other materials provided with the
#    distribution.
# 3. Neither the name of the JPackage Project nor the names of its
#    contributors may be used to endorse or promote products derived
#    from this software without specific prior written permission.
#
# THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
# "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
# LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
# A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
# OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
# SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
# LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
# DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
# THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
# (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
# OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.


# Configuration for rpmbuild, might be specified by options
# like e.g. 'rpmbuild --define "runselftest 0"'.

%{!?runselftest:%global runselftest 1}


%global section		devel
%global upstreamrel	git
%global source_path	pgjdbc/src/main/java/org/postgresql
%global parent_ver	1.0.8
%global parent_poms_builddir	./pgjdbc-parent-poms-REL%parent_ver

%global pgjdbc_mvn_options -DwaffleEnabled=false -DosgiEnabled=false \\\
    -DexcludePackageNames=org.postgresql.osgi:org.postgresql.sspi

Summary:	JDBC driver for PostgreSQL
Name:		postgresql-jdbc
Version:	9.5.%{upstreamrel}
Release:	1%{?dist}
# ASL 2.0 applies only to postgresql-jdbc.pom file, the rest is BSD
License:	BSD and ASL 2.0
Group:		Applications/Databases
URL:		http://jdbc.postgresql.org/

SOURCE0:	%{name}-%{version}.tar.gz
Source1:	postgres-testing.sh

# Upstream moved parent pom.xml into separate project (even though there is only
# one dependant project on it?).  Let's try to not complicate packaging by
# having separate spec file for it, too.
Source2:	https://github.com/pgjdbc/pgjdbc-parent-poms/archive/REL%parent_ver.tar.gz

Patch1:		hack-parents.patch

BuildArch:	noarch
BuildRequires:	java-devel >= 1.8
BuildRequires:	jpackage-utils
BuildRequires:	maven-local
BuildRequires:	java-comment-preprocessor
BuildRequires:	properties-maven-plugin

%if %runselftest
BuildRequires:	postgresql-server
BuildRequires:	postgresql-contrib
%endif

# gettext is only needed if we try to update translations
#BuildRequires:	gettext
Requires:	jpackage-utils
Requires:	java-headless >= 1:1.8

%description
PostgreSQL is an advanced Object-Relational database management
system. The postgresql-jdbc package includes the .jar files needed for
Java programs to access a PostgreSQL database.


%package	parent-poms
Summary:	Build dependency management for pgjdbc.

%description parent-poms
Pom files bringing dependencies required for successful PostgreSQL JDBC driver
build.


%package javadoc
Summary:	API docs for %{name}
Group:		Documentation

%description javadoc
This package contains the API Documentation for %{name}.


%prep
%setup -c -q -a 2

mv %name-%version/* .
pwd

# remove any binary libs
find -name "*.jar" -or -name "*.class" | xargs rm -f


%pom_disable_module ubenchmark

# Hack #0!  For upstream it is to some extent important to have the parent-poms
# project separated.  Having it like that on downstream level does not help at
# all.  Note that we have to revert this patch before we do the installation.
%patch1 -p1 -b.hack-parent-poms

# Hack #1!  This directory is missing for some reason, it is most probably some
# misunderstanding between maven, maven-compiler-plugin and
# java-comment-preprocessor?  Not solved yet.  See rhbz#1325060.
mkdir -p pgjdbc/target/generated-sources/annotations


%build
# Ideally we would run "sh update-translations.sh" here, but that results
# in inserting the build timestamp into the generated messages_*.class
# files, which makes rpmdiff complain about multilib conflicts if the
# different platforms don't build in the same minute.  For now, rely on
# upstream to have updated the translations files before packaging.

# Include PostgreSQL testing methods and variables.
%if %runselftest
. %{SOURCE1}

PGTESTS_LOCALE=C.UTF-8

cat <<EOF > build.local.properties
server=localhost
port=$PGTESTS_PORT
database=test
username=test
password=test
privilegedUser=$PGTESTS_ADMIN
privilegedPassword=$PGTESTS_ADMINPASS
preparethreshold=5
loglevel=0
protocolVersion=0
EOF

# Start the local PG cluster.
pgtests_start
%endif

# First "build" the parent-poms ..
cd %parent_poms_builddir
%mvn_build -- %pgjdbc_mvn_options
cd ..
# .. and then build pgjdbc.

%if %runselftest
%mvn_build -- %pgjdbc_mvn_options
%else
%mvn_build -- %pgjdbc_mvn_options -Dmaven.test.skip=true
%endif

# Hack #0!  Revert the patch above.
for i in `find -name '*.hack-parent-poms'`
do
	mv $i ${i%%%%.hack-parent-poms}
done


%install
%mvn_install
cd %parent_poms_builddir
%mvn_install

pushd $RPM_BUILD_ROOT%{_javadir}
# Also, for backwards compatibility with our old postgresql-jdbc packages,
# add these symlinks.  (Probably only the jdbc3 symlink really makes sense?)
ln -s %{name}/postgresql.jar postgresql-jdbc.jar
ln -s %{name}/postgresql.jar postgresql-jdbc2.jar
ln -s %{name}/postgresql.jar postgresql-jdbc2ee.jar
ln -s %{name}/postgresql.jar postgresql-jdbc3.jar
popd


%check


%files -f .mfiles
%license LICENSE
%doc README.md
%{_javadir}/%{name}.jar
%{_javadir}/%{name}2.jar
%{_javadir}/%{name}2ee.jar
%{_javadir}/%{name}3.jar

%files parent-poms -f %parent_poms_builddir/.mfiles

%files javadoc
%license LICENSE
%doc %{_javadocdir}/%{name}


%changelog
* Wed Jun 01 2016 Pavel Raiskup <praiskup@redhat.com> - 9.5.git-1
- update to work with tarball from git version of jdbc

* Wed Apr 13 2016 Pavel Raiskup <praiskup@redhat.com> - 9.4.1208-8
- merge parent-poms and pgjdbc downstream (having separate package does not seem
  to be necessary at all)

* Tue Apr 12 2016 Pavel Raiskup <praiskup@redhat.com> - 9.4.1208-7
- fix testsuite for fedora 24

* Tue Apr 12 2016 Pavel Kajaba <pkajaba@redhat.com> - 9.4.1208-6
- Added script to enable testing

* Fri Apr 08 2016 Pavel Raiskup <praiskup@redhat.com> - 9.4.1208-5
- enable testsuite for each build, to-be-fixed-yet

* Fri Apr 08 2016 Pavel Raiskup <praiskup@redhat.com> - 9.4.1208-4
- apply the work-around for maven-compiler-plugin && jcp issue

* Fri Apr 08 2016 Pavel Raiskup <praiskup@redhat.com> - 9.4.1208-3
- bump

* Thu Jun 18 2015 Fedora Release Engineering <rel-eng@lists.fedoraproject.org> - 9.4.1200-2
- Rebuilt for https://fedoraproject.org/wiki/Fedora_23_Mass_Rebuild

* Wed Feb 04 2015 Pavel Raiskup <praiskup@redhat.com> - 9.4.1200-1
- rebase to most recent version (#1188827)

* Mon Jul 14 2014 Pavel Raiskup <praiskup@redhat.com> - 9.3.1102-1
- Rebase to most recent version (#1118667)
- revert back upstream commit for travis build

* Sat Jun 07 2014 Fedora Release Engineering <rel-eng@lists.fedoraproject.org> - 9.3.1101-4
- Rebuilt for https://fedoraproject.org/wiki/Fedora_21_Mass_Rebuild

* Mon May 19 2014 Pavel Raiskup <praiskup@redhat.com> - 9.3.1101-3
- run upstream testsuite when '%%runselftest' defined

* Wed Apr 23 2014 Mikolaj Izdebski <mizdebsk@redhat.com> - 9.3.1101-2
- Add explicit requires on java-headless

* Wed Apr 23 2014 Pavel Raiskup <praiskup@redhat.com> - 9.3.1101-1
- Rebase to most recent version (#1090366)

* Fri Mar 28 2014 Michael Simacek <msimacek@redhat.com> - 9.2.1002-5
- Use Requires: java-headless rebuild (#1067528)

* Tue Aug 06 2013 Pavel Raiskup <praiskup@redhat.com> - 9.2.1002-4
- add javadoc subpackage

* Tue Aug 06 2013 Pavel Raiskup <praiskup@redhat.com> - 9.2.1002-4
- don't use removed macro %%add_to_maven_depmap (#992816)
- lint: trim-lines, reuse %%{name} macro, fedora-review fixes
- merge cleanup changes by Stano Ochotnicky

* Sun Aug 04 2013 Fedora Release Engineering <rel-eng@lists.fedoraproject.org> - 9.2.1002-3
- Rebuilt for https://fedoraproject.org/wiki/Fedora_20_Mass_Rebuild

* Thu Feb 14 2013 Fedora Release Engineering <rel-eng@lists.fedoraproject.org> - 9.2.1002-2
- Rebuilt for https://fedoraproject.org/wiki/Fedora_19_Mass_Rebuild

* Wed Nov 14 2012 Tom Lane <tgl@redhat.com> 9.2.1002-1
- Update to build 9.2-1002 (just to correct mispackaging of source tarball)

* Tue Nov 13 2012 Tom Lane <tgl@redhat.com> 9.2.1001-1
- Update to build 9.2-1001 for compatibility with PostgreSQL 9.2

* Sun Jul 22 2012 Tom Lane <tgl@redhat.com> 9.1.902-1
- Update to build 9.1-902

* Sat Jul 21 2012 Fedora Release Engineering <rel-eng@lists.fedoraproject.org> - 9.1.901-4
- Rebuilt for https://fedoraproject.org/wiki/Fedora_18_Mass_Rebuild

* Thu Feb 23 2012 Tom Lane <tgl@redhat.com> 9.1.901-3
- Change BuildRequires: java-1.6.0-openjdk-devel to just java-devel.
  As of 9.1-901, upstream has support for JDBC4.1, so we don't have to
  restrict to JDK6 anymore, and Fedora is moving to JDK7
Resolves: #796580

* Sat Jan 14 2012 Fedora Release Engineering <rel-eng@lists.fedoraproject.org> - 9.1.901-2
- Rebuilt for https://fedoraproject.org/wiki/Fedora_17_Mass_Rebuild

* Mon Sep 12 2011 Tom Lane <tgl@redhat.com> 9.1.901-1
- Update to build 9.1-901 for compatibility with PostgreSQL 9.1

* Mon Aug 15 2011 Tom Lane <tgl@redhat.com> 9.0.801-4
- Add BuildRequires: java-1.6.0-openjdk-devel to ensure we have recent JDK
Related: #730588
- Remove long-obsolete minimum versions from BuildRequires

* Sun Jul 17 2011 Tom Lane <tgl@redhat.com> 9.0.801-3
- Switch to non-GCJ build, since GCJ is now deprecated in Fedora
Resolves: #722247
- Use %%{_mavendepmapfragdir} to fix FTBFS with maven 3

* Wed Feb 09 2011 Fedora Release Engineering <rel-eng@lists.fedoraproject.org> - 9.0.801-2
- Rebuilt for https://fedoraproject.org/wiki/Fedora_15_Mass_Rebuild

* Wed Dec 29 2010 Tom Lane <tgl@redhat.com> 9.0.801-1
- Update to build 9.0-801

* Mon May 31 2010 Tom Lane <tgl@redhat.com> 8.4.701-4
- Update gcj_support sections to meet Packaging/GCJGuidelines;
  fixes FTBFS in F-14 rawhide

* Tue Nov 24 2009 Tom Lane <tgl@redhat.com> 8.4.701-3
- Seems the .pom file *must* have a package version number in it, sigh
Resolves: #538487

* Mon Nov 23 2009 Tom Lane <tgl@redhat.com> 8.4.701-2
- Add a .pom file to ease use by maven-based packages (courtesy Deepak Bhole)
Resolves: #538487

* Tue Aug 18 2009 Tom Lane <tgl@redhat.com> 8.4.701-1
- Update to build 8.4-701

* Sun Jul 26 2009 Fedora Release Engineering <rel-eng@lists.fedoraproject.org> - 0:8.3.603-4
- Rebuilt for https://fedoraproject.org/wiki/Fedora_12_Mass_Rebuild

* Tue Apr 21 2009 Tom Lane <tgl@redhat.com> 8.3.603-3
- Avoid multilib conflict caused by overeager attempt to rebuild translations

* Thu Feb 26 2009 Fedora Release Engineering <rel-eng@lists.fedoraproject.org> - 0:8.3.603-2.1
- Rebuilt for https://fedoraproject.org/wiki/Fedora_11_Mass_Rebuild

* Wed Jul  9 2008 Tom "spot" Callaway <tcallawa@redhat.com> 8.3.603-1.1
- drop repotag

* Tue Feb 12 2008 Tom Lane <tgl@redhat.com> 8.3.603-1jpp
- Update to build 8.3-603

* Sun Aug 12 2007 Tom Lane <tgl@redhat.com> 8.2.506-1jpp
- Update to build 8.2-506

* Tue Apr 24 2007 Tom Lane <tgl@redhat.com> 8.2.505-1jpp
- Update to build 8.2-505
- Work around 1.4 vs 1.5 versioning inconsistency

* Fri Dec 15 2006 Tom Lane <tgl@redhat.com> 8.2.504-1jpp
- Update to build 8.2-504

* Wed Aug 16 2006 Tom Lane <tgl@redhat.com> 8.1.407-1jpp.4
- Fix Requires: for rebuild-gcj-db (bz #202544)

* Wed Aug 16 2006 Fernando Nasser <fnasser@redhat.com> 8.1.407-1jpp.3
- Merge with upstream

* Sat Jul 22 2006 Jakub Jelinek <jakub@redhat.com> 8.1.407-1jpp.2
- Rebuilt

* Wed Jul 12 2006 Jesse Keating <jkeating@redhat.com> - 0:8.1.407-1jpp.1
- rebuild

* Wed Jun 14 2006 Tom Lane <tgl@redhat.com> 8.1.407-1jpp
- Update to build 8.1-407

* Mon Mar 27 2006 Tom Lane <tgl@redhat.com> 8.1.405-2jpp
- Back-patch upstream fix to support unspecified-type strings.

* Thu Feb 16 2006 Tom Lane <tgl@redhat.com> 8.1.405-1jpp
- Split postgresql-jdbc into its own SRPM (at last).
- Build it from source.  Add support for gcj compilation.
