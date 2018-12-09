
CERTIFICATES := root_ca client client_ca client-revoked
SSLFILES := $(CERTIFICATES:%=ssl/%.key) $(CERTFICATES:%=ssl/%.crt) \
	ssl/client.crl \
	ssl/client_ca.pk8
	
sslfiles : $(SSLFILES)

ssl:
	mkdir ssl

ssl/new_certs_dir:
	mkdir ssl/new_certs_dir
	
ssl/%.key: ssl
	openssl genrsa -out $@ 1024
	chmod 0600 $@

# Root CA certificate
ssl/root_ca.crt: ssl/root_ca.key cas.config
	touch ssl/root_ca-certindex
	openssl req -new -out ssl/root_ca.crt -x509 -config cas.config -config root_ca.config -key ssl/root_ca.key -days 10000 -extensions v3_ca
	echo "01" > ssl/root_ca.srl

# Client and server CAs
ssl/%_ca.crt: ssl/%_ca.key %_ca.config ssl/root_ca.crt ssl/new_certs_dir
	touch ssl/$*_ca-certindex
	echo "unique_subject=no" > ssl/$*_ca-certindex.attr
	openssl req -new -out ssl/temp_ca.crt -config cas.config -config $*_ca.config -key ssl/$*_ca.key
# Sign the certificate with the root CA
	openssl ca -name root_ca -batch -config cas.config -in ssl/temp_ca.crt -out ssl/temp_ca_signed.crt -extensions v3_ca
	openssl x509 -in ssl/temp_ca_signed.crt -out ssl/$*_ca.crt # to keep just the PEM cert
	rm ssl/temp_ca.crt ssl/temp_ca_signed.crt
	echo "01" > ssl/$*_ca.srl

ssl/client_ca.pk8: ssl/client_ca.key
	openssl pkcs8 -topk8 -nocrypt -in ssl/client_ca.key -out ssl/client_ca.pk8 -outform DER -v1 PBE-MD5-DES
	
# Client certificate, signed by the client CA:
ssl/client.crt: ssl/client.key ssl/client_ca.crt
	openssl req -new -key ssl/client.key -out ssl/client.csr -config client.config
	openssl ca -name client_ca -batch -out ssl/temp.crt -config cas.config -infiles ssl/client.csr
	openssl x509 -in ssl/temp.crt -out ssl/client.crt # to keep just the PEM cert
	rm ssl/client.csr ssl/temp.crt

# Another client certificate, signed by the client CA. This one is revoked.
ssl/client-revoked.crt: ssl/client-revoked.key ssl/client_ca.crt client.config
	openssl req -new -key ssl/client-revoked.key -out ssl/client-revoked.csr -config client.config
	openssl ca -name client_ca -batch -out ssl/temp.crt -config cas.config -infiles ssl/client-revoked.csr
	openssl x509 -in ssl/temp.crt -out ssl/client-revoked.crt # to keep just the PEM cert
	rm ssl/client-revoked.csr ssl/temp.crt
	
#### CRLs

ssl/client.crl: ssl/client-revoked.crt
	openssl ca -config cas.config -name client_ca -revoke ssl/client-revoked.crt
	openssl ca -config cas.config -name client_ca -gencrl -out ssl/client.crl
