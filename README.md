elasticsearch-license
=====================

Internal Elasticsearch Licensing Plugin

## Command Line tools (Interface with Internal License Server)

**Note:** Initially run `mvn clean compile package` to use the scripts

### bin/key-pair-generator

Generates a 2048-bit RSA public/private key pair to be used for license generation & validation

**Note:** Errors if provided file paths for public/private already exists

**Output** - public/private key in the location provided with the provided key password

**Options:**
`--publicKeyPath` - path to store the public key
`--privateKeyPath` - path to store the private key
`--keyPass` - password for the key pair

**Example Usage:**
```bash
$ bin/key-pair-generator --publicKeyPath ~/.es_temp_license/pub.key --privateKeyPath ~/.es_temp_license/pri.key --keyPass ab
```
Outputs the public key to `~/.es_temp_license/pub.key`, private key to `~/.es_temp_license/pri.key` securing the key pair with the provided `--keyPass` of `ab`

### bin/license-generator
Generates a signed license given a licensing spec and keyPair location

**Note:** a `license spec` (see format below) can be provided in two ways, using `--license` allows for passing the spec as a string using `--licenseFile` allows passing in a file that has the license spec

**Output** - a signed license based on the provided `license spec`, private/public key location and password

**Options:**
`--license` - license spec as a string (optional when `--licenseFile` is provided)
`--licenseFile` - path to a license spec file (optional when `--license` is provided)
`--publicKeyPath` - path to retrieve the public key
`--privateKeyPath` - path to retrieve the private key
`--keyPass` - password for the key pair provided

**License Spec format:**
```
{
    "licenses": [
        {
            "type": STRING (“trial” | “internal” | “subscription”),
            "subscription_type": STRING (“none” | “gold” | “silver” | “platinum”),
            "issued_to": STRING,
            "issuer": STRING,
            "issue_date": STRING (format: “YYYY-MM-DD”),
            "expiry_date": STRING (format: “YYYY-MM-DD”),
            "feature": STRING (“shield” | “marvel”),
            "max_nodes": INT
        },
        {
	    ...
        }
    ]
}
```

**Example Usage:**
```bash
# license spec file

$ cat license_spec.json
{
    "licenses": [
        {
            "type": "internal",
            "subscription_type": "none",
            "issued_to": "issuedTo",
            "issuer": "issuer",
            "issue_date": "2014-09-29",
            "expiry_date": "2015-08-29",
            "feature": "shield",
            "max_nodes": 1
        }
    ]
}

# generate a signed license according to license_spec.json using the private/public keypair 

$ bin/license-generator --publicKeyPath ~/.es_temp_license/pub.key --privateKeyPath ~/.es_temp_license/pri.key --keyPass ab --licenseFile license_spec.json > gen_license.json

# generated license for license_spec.json

$ cat gen_license.json
{
  "licenses" : [ {
    "uid" : "d8bcf9e8-bcb0-4f72-81ca-8a7537a436c5",
    "type" : "internal",
    "subscription_type" : "none",
    "issued_to" : "issuedTo",
    "issue_date" : "2014-09-29",
    "expiry_date" : "2015-08-29",
    "feature" : "shield",
    "max_nodes" : 1,
    "signature" : "naPgicfKM2+IJ0AoYgAAAG0AAAAAVGdIQ01qZUtCeEZNbS8wcTF4RU5mYUpiY01hdFlQNEVkdFJhYitoZndrSTI5eVZrY3ZRZ3lYU0s1QWdYb0Y5d1dBQmRUK01leE1aR0RUOHhoRVVhVUE9PaztAAVzcgAxbmV0Lm5pY2hvbGFzd2lsbGlhbXMuamF2YS5saWNlbnNpbmcuU2lnbmVkTGljZW5zZYqE/59+smqEAgACWwAObGljZW5zZUNvbnRlbnR0AAJbQlsAEHNpZ25hdHVyZUNvbnRlbnRxAH4AAXhwdXIAAltCrPMX+AYIVOACAAB4cAAAAMCsH5r77/8FtWY+JxKd9MiBTYQLcXgmXMm+Y83VaNwmlr1lASJ2yf7rWojiuHTWemtUNtOZcXeSrLfs/oKwBzXIfvEZV8X/vPCWnpi7VtU4Hp+OZUFO4c0NQ1PnVdDk1uns16Dqe99/ota3FSvdFrmlzkz2E+2bbx0fwWbKnGDXFXy6eE7OISRJdCqa8gljMo9PA1+RI7MFQ8bSzs9up0cEkSuPzgtafFW5zfyn2vpoPZTxDpJslTBk7S3mdchE0eJ1cQB+AAMAAAEAdikZHpJVMxWMxNsksYnNOD7F+15SK3MCtUWJnQdhYCuVHdKQUE3YxWv59QQuDmKuLbnvi0DsuPGlq3hEx0AXmbpaBOhkwTv3DKZH7V6C0YmXj7RLZobaDTtGY2pwV6Qf5+teq5dV493a1k6YGFiwUoERuWQxqmA36naLdVo2diCSh8QmZ4ihKnhqxwswh2TlnCVuaNN3E7HuGeE0wYgFEfgISJOFlEOnLOItRlrQOTzCq+mhASKbANxx/Z42eMGrgs+GJsxYQZfnBh8K3NQFQk2SjWR1sEgqUPXC+0Z7ungzkkwoSBbrdJfRPKbqXFDthWI1DY9SSZnTbwpUC2XA6Q=="
  } ]
}
```
 
### bin/verify-license
Verifies provided generated `license(s)` and outputs an *effective* licenses file

This tool can be used for the following:
 - ensure a given generated license is valid (has not been tampered with)
 - merge multiple licenses file for a customer into one *effective* licenses

**Effective License:**
One licenses that only retains effective sub-licenses for all the licenses provided. Where effective sub-licenses are identified as the sub-licenses with the latest `expiry_date` for a `feature` and the sub-license has not already expired.

**Output** - an effective license of all the provided generated license file(s)

**Options:**
`--licensesFiles` - a set of **generated** licenses files separated by `:`
`--publicKeyPath` -  path to retrieve the public key
`--keyPass` - password for the key pair provided

**Example Usage:**

```
# the output will be the same as the content of gen_license.json (as all the licenses are valid and not expired)
# in order to merge multiple licenses file use --licensesFiles file1.json:file2.json

$ bin/verify-license --publicKeyPath ~/.es_temp_license/pub.key --keyPass ab --licensesFiles gen_license.json
```
