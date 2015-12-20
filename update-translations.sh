#!/bin/sh
#

mvn clean
find . -name '*.java' -o -name '*.java.in' > translation.filelist
xgettext -k -kGT.tr -F -f translation.filelist -L Java -o src/main/resources/org/postgresql/translation/messages.pot --from-code=utf-8
rm translation.filelist

for i in src/main/java/org/postgresql/translation/*.po
do
	msgmerge --backup=none -U $i src/main/resources/org/postgresql/translation/messages.pot
	polang=`basename $i .po`
	msgfmt -j -l $polang -r org.postgresql.translation.messages -d src/main/resources $i
done
