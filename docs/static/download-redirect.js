// Redirect /download/postgresql-*.jar to Maven Central
if (window.location.pathname.startsWith('/download/postgresql-')) {
  const filename = window.location.pathname.split('/').pop();
  const match = filename.match(/^postgresql-(.+)\.jar$/);
  
  if (match) {
    const version = match[1];
    const mavenUrl = `https://repo1.maven.org/maven2/org/postgresql/postgresql/${version}/${filename}`;
    window.location.replace(mavenUrl);
  }
}
