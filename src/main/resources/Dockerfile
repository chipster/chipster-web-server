FROM java:8 


RUN groupadd -r chipster && useradd -r -g chipster chipster

WORKDIR /opt/chipster/sessionstorage
RUN chown -R chipster:chipster /opt/chipster

USER chipster

COPY dist/*.jar lib/

EXPOSE 8080 8081 8082  
CMD ["java", "-cp", "lib/*:","fi.csc.chipster.rest.ServerLauncher"]