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
%global source_path	pgjdbc/src/main/java/org/postgresql

Summary:	JDBC driver for PostgreSQL
Name:		postgresql-jdbc
Version:	GENERATED
Release:	GENERATED
License:	BSD
URL:		http://jdbc.postgresql.org/

# This archive contains the minimal set of files + pom.xml that can be used to build postgresql.jar
Source0:    https://repo1.maven.org/maven2/org/postgresql/postgresql/%{version}/postgresql-%{version}-src.tar.gz
Provides:	pgjdbc = %version-%release

BuildArch:	noarch
BuildRequires:	java-devel >= 1.8
BuildRequires:	maven-local
BuildRequires:	java-comment-preprocessor
BuildRequires:	maven-plugin-bundle
BuildRequires:	classloader-leak-test-framework

BuildRequires:	mvn(com.ongres.scram:client)
BuildRequires:	mvn(org.apache.maven.plugins:maven-clean-plugin)
BuildRequires:	mvn(org.apache.maven.surefire:surefire-junit-platform)
BuildRequires:	mvn(org.junit.jupiter:junit-jupiter-api)
BuildRequires:	mvn(org.junit.jupiter:junit-jupiter-engine)
BuildRequires:	mvn(org.junit.jupiter:junit-jupiter-params)
BuildRequires:	mvn(org.junit.vintage:junit-vintage-engine)

%if %runselftest
BuildRequires:	postgresql-contrib
BuildRequires:	postgresql-test-rpm-macros
%endif

# gettext is only needed if we try to update translations
#BuildRequires:	gettext

Obsoletes:	%{name}-parent-poms < 42.2.2-2

%description
PostgreSQL is an advanced Object-Relational database management
system. The postgresql-jdbc package includes the .jar files needed for
Java programs to access a PostgreSQL database.


%package javadoc
Summary:	API docs for %{name}

%description javadoc
This package contains the API Documentation for %{name}.


%prep
%setup -c -q

# remove any binary libs
find -name "*.jar" -or -name "*.class" | xargs rm -f

# compat symlink: requested by dtardon (libreoffice), reverts part of
# 0af97ce32de877 commit.
%mvn_file org.postgresql:postgresql %{name}/postgresql %{name} postgresql

# For compat reasons, make Maven artifact available under older coordinates.
%mvn_alias org.postgresql:postgresql postgresql:postgresql

%build
# Ideally we would run "sh update-translations.sh" here, but that results
# in inserting the build timestamp into the generated messages_*.class
# files, which makes rpmdiff complain about multilib conflicts if the
# different platforms don't build in the same minute.  For now, rely on
# upstream to have updated the translations files before packaging.

# Include PostgreSQL testing methods and variables.
%if %runselftest
%postgresql_tests_init

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
%postgresql_tests_start
%else
# -f is equal to -Dmaven.test.skip=true
opts="-f"
%endif

%mvn_build $opts


%install
%mvn_install


%files -f .mfiles
%license LICENSE
%doc README.md


%files javadoc -f .mfiles-javadoc
%license LICENSE


%changelog
* Tue Mar 03 2020 Vladimir Sitnikov <sitnikov.vladimir@gmail.com>
- Adapted to building from the source release
* Wed Nov 29 2017 Pavel Raiskup <praiskup@redhat.com> - 9.5.git
- no changelog in this spec file (upstream git)
