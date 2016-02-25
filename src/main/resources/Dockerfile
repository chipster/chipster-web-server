FROM java:8 


RUN groupadd -r chipster && useradd -r -g chipster chipster

WORKDIR /opt/chipster
RUN chown -R chipster:chipster /opt/chipster

USER chipster

COPY dist/*.jar lib/

COPY dist/chipster-tools-*.tar.gz /opt/chipster/

EXPOSE 8000
EXPOSE 8001
  
CMD ["java", "-cp", "lib/*:","fi.csc.chipster.rest.ServerLauncher"]