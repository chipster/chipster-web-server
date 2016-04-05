FROM java:8

RUN apt-get update
RUN apt-get install -y ant

RUN groupadd -r chipster && useradd -r -g chipster -u 1001 chipster

WORKDIR /home/chipster
RUN mkdir -p /opt/chipster-web-server/lib
RUN mkdir /opt/chipster-web
RUN mkdir /opt/chipster-tools

RUN chown -R chipster:chipster /home/chipster
RUN chown -R chipster:chipster /opt/chipster-*
RUN chmod -R ugo+rwx /opt/chipster-web-server

USER 1001

# clone main projects to the home dir for building
RUN git clone --branch ${CHIPSTER_BRANCH:-master} https://github.com/chipster/chipster.git --depth=1
RUN git clone --branch ${API_BRANCH:-openshift} https://github.com/chipster/chipster-web-server.git --depth=1

# no need to build these projects, clone directly to /opt
RUN cd /opt; git clone --branch ${TOOLS_BRANCH:-master} https://github.com/chipster/chipster-tools.git --depth=1
RUN cd /opt; git clone --branch ${CLIENT_BRANCH:-master} https://github.com/chipster/chipster-web.git --depth=1

# build the old chipster project
RUN cd chipster; ant package-jar
RUN cp chipster/dist/chipster-*.jar /opt/chipster-web-server/lib

# build the new chipster project (use gradlew because the gradle package from apt-get depends on java7)
RUN cd chipster-web-server; ./gradlew distTar
RUN tar -xf chipster-web-server/build/distributions/chipster-web-server.tar
RUN cp chipster-web-server/lib/*.jar /opt/chipster-web-server/lib

RUN export proxy_bind=8080

#EXPOSE 8080
#EXPOSE 8001

WORKDIR /opt/chipster-web-server  
#CMD ["java", "-cp", "lib/*:","fi.csc.chipster.rest.ServerLauncher"]
CMD java -cp lib/*: ${JAVA_CLASS:-fi.csc.chipster.rest.ServerLauncher}
