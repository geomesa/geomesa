#!/bin/bash
# Abort on Error
set -e

export PING_SLEEP=60s
export WORKDIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
export BUILD_OUTPUT=$WORKDIR/build.out

touch $BUILD_OUTPUT

# set up a repeating loop to send some output to Travis
echo [INFO] $(date -u '+%F %T UTC') - build starting
bash -c "while true; do sleep $PING_SLEEP; echo [INFO] \$(date -u '+%F %T UTC') - build continuing...; done" &
PING_LOOP_PID=$!

# build using the maven executable, not the zinc maven compiler (which uses too much memory)
# run test instead of install to save time building tar.gzs and shaded jars
mvn clean license:check package -Dmaven.test.skip -Phbase,travis-ci 2>&1 | tee -a $BUILD_OUTPUT | grep -e '^\[INFO\] Building GeoMesa' -e '^\[INFO\] --- \(maven-surefire-plugin\|maven-install-plugin\|scala-maven-plugin.*:compile\)'
RESULT=${PIPESTATUS[0]} # capture the status of the maven build

# dump out the end of the build log, to show success or errors
tail -500 $BUILD_OUTPUT

# validate CQs
if [[ $RESULT -eq 0 ]]; then
  # calculate CQs
  bash ${WORKDIR}/build/calculate-cqs.sh
  cqs=$(git diff ${WORKDIR}/build/cqs.tsv)
  if [[ ! "${cqs}" == "" ]]; then
    drg="                                       \$,  \$,     ,                         \n                                        \"ss.\$ss. .s'                         \n                                ,     .ss\$\$\$\$\$\$\$\$\$\$s,                        \n                                \$. s\$\$\$\$\$\$\$\$\$\$\$\$\$\$'\$\$Ss                      \n                                \"\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$o\$\$\$       ,              \n                               s\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$s,  ,s               \n                              s\$\$\$\$\$\$\$\$\$\"\$\$\$\$\$\$\"\"\"\"\$\$\$\$\$\$\"\$\$\$\$\$,             \n                              s\$\$\$\$\$\$\$\$\$\$s\"\"\$\$\$\$ssssss\"\$\$\$\$\$\$\$\$\"             \n                             s\$\$\$\$\$\$\$\$\$\$'          \"\"\"ss\"\$\"\$s\"\"              \n                             s\$\$\$\$\$\$\$\$\$\$,               \"\"\"\"\"\$  .s\$\$s        \n                             s\$\$\$\$\$\$\$\$\$\$\$\$s,...                s\$\$'          \n                          ssss\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$####s.     .\$\$\"\$.   , s-   \n                            \"\"\"\"\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$#####\$\$\$\$\$\$\"     \$.\$'    \n                                 \"\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$####s\"\"     .\$\$\$|     \n                                   \"\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$##s    .\$\$\" \$    \n                                   \$\$\"\"\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\"        \n                                  \$\$\"  \"\$\"\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$S\"\"\"\"'         \n                             ,   ,\"     '  \$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$####s             \n                             \$.          .s\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$####\"            \n                 ,           \"\$s.   ..ssS\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$####\"            \n                 \$           .\$\$\$S\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$#####\"             \n                 Ss     ..sS\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$######\"\"              \n                  \"\$\$sS\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$########\"                  \n           ,      s\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$#########\"\"'                      \n           \$    s\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$#######\"\"'      s'         ,           \n           \$\$..\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$######\"'       ....,\$\$....    ,\$            \n            \"\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$######\"' ,     .sS\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$s\$\$            \n              \$\$\$\$\$\$\$\$\$\$\$\$#####\"     \$, .s\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$s.         \n   )          \$\$\$\$\$\$\$\$\$\$\$#####'       \$\$\$\$\$\$\$\$\$###########\$\$\$\$\$\$\$\$\$\$\$.       \n  ((          \$\$\$\$\$\$\$\$\$\$\$#####       \$\$\$\$\$\$\$\$###\"       \"####\$\$\$\$\$\$\$\$\$\$      \n  ) \\         \$\$\$\$\$\$\$\$\$\$\$\$####.     \$\$\$\$\$\$###\"             \"###\$\$\$\$\$\$\$\$\$   s'\n (   )        \$\$\$\$\$\$\$\$\$\$\$\$\$####.   \$\$\$\$\$###\"                ####\$\$\$\$\$\$\$\$s\$\$' \n )  ( (       \$\$\"\$\$\$\$\$\$\$\$\$\$\$#####.\$\$\$\$\$###'                .###\$\$\$\$\$\$\$\$\$\$\"   \n (  )  )   _,\$\"   \$\$\$\$\$\$\$\$\$\$\$\$######.\$\$##'                .###\$\$\$\$\$\$\$\$\$\$     \n ) (  ( \\.         \"\$\$\$\$\$\$\$\$\$\$\$\$\$#######,,,.          ..####\$\$\$\$\$\$\$\$\$\$\$\"     \n(   )\$ )  )        ,\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$####################\$\$\$\$\$\$\$\$\$\$\$\"       \n(   (\$\$  ( \\     _sS\"   \"\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$S\$\$,       \n )  )\$\$\$s ) )  .      .    \$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\"'   \$\$      \n  (   \$\$\$Ss/  .\$,    .\$,,s\$\$\$\$\$\$##S\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$S\"\"        '      \n    \\)_\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$##\"  \$\$         \$\$.         \$\$.                \n         \"S\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$\$#\"      \$           \$           \$                \n             \"\"\"\"\"\"\"\"\"\"\"\"\"'         '           '           '                " # Credit - Tua Xiong
    echo "[ERROR] ----------------------- HERE BE DRAGONS -------------------- [ERROR]"
    echo -e "${drg}"
    echo "[ERROR] CQ Checked failed!"
    RESULT=1
  fi
fi

if [[ $RESULT -ne 0 ]]; then
  echo -e "[ERROR] Build failed!\n"
fi

# nicely terminate the ping output loop
kill $PING_LOOP_PID

# exit with the result of the maven build to pass/fail the travis build
exit $RESULT
