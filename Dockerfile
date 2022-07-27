FROM openjdk:11-jre-slim
EXPOSE 8081
COPY target/pubsub-docker.jar ./pubsub-docker.jar
ENTRYPOINT ["java", "-jar"]
CMD [ "./pubsub-docker.jar" ]