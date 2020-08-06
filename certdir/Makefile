ROOT_CA_PASSWORD=ssl_ca_pwd
PK8_PASSWORD=sslpwd
P12_PASSWORD=sslpwd
SERVER_CRT_DIR=server/

all : $(SERVER_CRT_DIR)root.key $(SERVER_CRT_DIR)root.crt $(SERVER_CRT_DIR)server.crt goodroot.crt goodclient badclient

goodclient: goodclient.crt goodclient.pk8 goodclient.p12

badclient: badclient.crt badclient.pk8 badclient.p12

.PHONY: clean
clean:
	@echo Removing certificate files
	@rm -f *.crt *.key *.csr *.srl *.p12 *.pk8
	@rm -rf $(SERVER_CRT_DIR)*.crt $(SERVER_CRT_DIR)*.key $(SERVER_CRT_DIR)*.csr $(SERVER_CRT_DIR)*.srl $(SERVER_CRT_DIR)*.p12 $(SERVER_CRT_DIR)*.pk8
	@echo

%.pk8 : %.key
	@echo Exporting key $@
	openssl pkcs8 -topk8 -in $< -out $@ -outform DER -v1 PBE-MD5-DES -passout pass:$(PK8_PASSWORD)

%.p12 : %.crt
	@echo Exporting certificate $@
	openssl pkcs12 -export -in $< -inkey $*.key -out $@ -name user -CAfile $(SERVER_CRT_DIR)root.crt -caname local -passout pass:$(P12_PASSWORD)

%root.key :
	@echo Generating CA key $@
	mkdir -p $(*D)
	openssl genrsa -aes256 -passout pass:$(ROOT_CA_PASSWORD) -out $@ 4096
	@echo

goodroot.crt : $(SERVER_CRT_DIR)
	cp $(SERVER_CRT_DIR)root.crt goodroot.crt

%root.crt : %root.key
	@echo Creating root certificate $@
	openssl req -x509 -new -nodes -key $< -passin pass:$(ROOT_CA_PASSWORD) -sha256 -days 1024 -out $@ -subj "/C=US/ST=CA/O=PgJdbc test/CN=root certificate"
	@echo


$(SERVER_CRT_DIR)server.crt : $(SERVER_CRT_DIR)root.key $(SERVER_CRT_DIR)root.crt
	$(eval $@_CERT_FILE := $(SERVER_CRT_DIR)server)
	@echo Creating good client certificate $@
	openssl genrsa -out $($@_CERT_FILE).key 2048
	openssl req -new -sha256 -key $($@_CERT_FILE).key -passin pass:$(ROOT_CA_PASSWORD) -subj "/C=US/ST=CA/O=PgJdbc tests/CN=localhost" -out $($@_CERT_FILE).csr
	openssl x509 -req -in $($@_CERT_FILE).csr -CA $(SERVER_CRT_DIR)root.crt -CAkey $(SERVER_CRT_DIR)root.key -passin pass:$(ROOT_CA_PASSWORD) -CAcreateserial -out $($@_CERT_FILE).crt -days 500 -sha256
	@rm $($@_CERT_FILE).csr
	@echo

goodclient.crt goodclient.key : $(SERVER_CRT_DIR)root.key $(SERVER_CRT_DIR)root.crt
	$(eval $@_CERT_FILE := goodclient)
	@echo Creating good client certificate $@
	openssl genrsa -out $($@_CERT_FILE).key 2048
	# CN=test has to match user name
	openssl req -new -sha256 -key $($@_CERT_FILE).key -subj "/C=US/ST=CA/O=PgJdbc tests/CN=test" -out $($@_CERT_FILE).csr
	openssl x509 -req -in $($@_CERT_FILE).csr -CA $(SERVER_CRT_DIR)root.crt -CAkey $(SERVER_CRT_DIR)root.key -passin pass:$(ROOT_CA_PASSWORD) -CAcreateserial -out $($@_CERT_FILE).crt -days 500 -sha256
	@rm $($@_CERT_FILE).csr
	@echo

badclient.crt badclient.key : badroot.key badroot.crt
	$(eval $@_CERT_FILE := badclient)
	@echo Creating bad client certificate $@
	openssl genrsa -out $($@_CERT_FILE).key 2048
	# CN=test has to match user name
	openssl req -new -sha256 -key $($@_CERT_FILE).key -subj "/C=US/ST=CA/O=PgJdbc tests/CN=test" -out $($@_CERT_FILE).csr
	openssl x509 -req -in $($@_CERT_FILE).csr -CA badroot.crt -CAkey badroot.key -passin pass:$(ROOT_CA_PASSWORD) -CAcreateserial -out $($@_CERT_FILE).crt -days 500 -sha256
	@rm $($@_CERT_FILE).csr
	@echo
