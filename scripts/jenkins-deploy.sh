#!/bin/bash

UPDATESITE=${QUALIFIER}

# FTP dirs are prefixed with FTP. Local dirs have no prefix.
REPOSITORY_DIR="${WORKSPACE}/ccw.product/target/repository"
PRODUCTS_DIR="${WORKSPACE}/ccw.product/target/products"

FTP_BRANCH_DIR=/www/updatesite/branch/${BRANCH}
FTP_UPDATESITE_DIR=${FTP_BRANCH_DIR}/${UPDATESITE}

echo "REPOSITORY_DIR:${REPOSITORY_DIR}"
echo "FTP_UPDATESITE_DIR:${FTP_UPDATESITE_DIR}"

# put p2 repository in the right branch / versioned subdirecty updatesite
# put documentation at the root of the update site so that it is self-documented
# put documentation at the root of the branch site to serve as the up to date generated documentation
lftp ftp://${FTP_USER}:${FTP_PASSWORD}@${FTP_HOST} <<EOF
set ftp:passive-mode true
mirror -R -e -v ${REPOSITORY_DIR}/ ${FTP_UPDATESITE_DIR}
mirror -R -e -v -x target ${WORKSPACE}/doc/target/html/ ${FTP_UPDATESITE_DIR}
mirror -R -e -v -x target ${WORKSPACE}/doc/target/html/ ${FTP_BRANCH_DIR}/doc
quit
EOF

test $? || exit $?

wget http://updatesite.ccw-ide.org/branch/${BRANCH}/${UPDATESITE}/content.jar || exit 1

wget http://updatesite.ccw-ide.org/branch/${BRANCH}/${UPDATESITE}/documentation.html || exit 1

## UPDATE The branch p2 repository by referencing this build's p2 repository
# Create compositeArtifacts.xml 
cat <<EOF > ${WORKSPACE}/compositeArtifacts.xml
<?xml version='1.0' encoding='UTF-8'?>
<?compositeArtifactRepository version='1.0.0'?>
<repository name='&quot;Counterclockwise Jenkins CI Last Build - Branch ${BRANCH}&quot;'
    type='org.eclipse.equinox.internal.p2.artifact.repository.CompositeArtifactRepository' version='1.0.0'>
  <properties size='1'>
    <property name='p2.timestamp' value='1243822502440'/>
  </properties>
  <children size='1'>
    <child location='./${UPDATESITE}'/>
  </children>
</repository>
EOF

# Create compositeContent.xml
cat <<EOF > ${WORKSPACE}/compositeContent.xml
<?xml version='1.0' encoding='UTF-8'?>
<?compositeMetadataRepository version='1.0.0'?>
<repository name='&quot;Counterclockwise Jenkins CI Last Build - Branch ${BRANCH}&quot;'
    type='org.eclipse.equinox.internal.p2.metadata.repository.CompositeMetadataRepository' version='1.0.0'>
  <properties size='1'>
    <property name='p2.timestamp' value='1243822502499'/>
  </properties>
  <children size='1'>
    <child location='./${UPDATESITE}'/>
  </children>
</repository>
EOF


test $? || exit $?

# Push branch p2 repository files via FTP
ftp -pn ${FTP_HOST} <<EOF
quote USER ${FTP_USER}
quote PASS ${FTP_PASSWORD}
bin
prompt off
lcd ${WORKSPACE}
cd ${FTP_UPDATESITE_ROOT}/${BRANCH}
put compositeArtifacts.xml
put compositeContent.xml
quit
EOF
test $? || exit $?

[ -d ${PRODUCTS_DIR} ] || ( echo "Skipping ftp publication of CCW products for missing directory ${PRODUCTS_DIR}"; exit 1; )

# Create directory products in ftp
ftp -pn ${FTP_HOST} <<EOF
quote USER ${FTP_USER}
quote PASS ${FTP_PASSWORD}
bin
prompt off
lcd ${PRODUCTS_DIR}
cd ${FTP_UPDATESITE_ROOT}/${BRANCH}/${UPDATESITE}
mkdir products 
quit
EOF

# iterate over the products to push in parallel

lftp ftp://${FTP_USER}:${FTP_PASSWORD}@${FTP_HOST} <<EOF
set ftp:passive-mode true
user ${FTP_USER} ${FTP_PASSWORD}
open ${FTP_HOST}
mirror -R -e -v ${PRODUCTS_DIR}/ ${FTP_UPDATESITE_DIR}/products
quit
EOF
wait

cd ${PRODUCTS_DIR}
PRODUCTS="`ls Counterclockwise*.zip`"
for PRODUCT in ${PRODUCTS}
do
    # --spider option only checks for file presence, without downloading it
    wget --spider http://updatesite.ccw-ide.org/branch/${UPDATESITE}/products/${PRODUCT}  || exit $?
done
