# Installs:
```shell
brew install maven

brew install openjdk@21

sudo ln -sfn /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk /Library/Java/JavaVirtualMachines/openjdk-21.jdk
```
[//]: # (Download otp-shaded jar file from [here]&#40;https://repo1.maven.org/maven2/org/opentripplanner/otp-shaded/2.5.0/otp-shaded-2.5.0.jar)
[//]: # (Move the downloaded jar to `execs` directory.)

# Build:
```shell
# Run at the root of the OpenTripPlanner repo
mvn clean package -DskipTests -Dprettier.skip=true
```

A target directory with jar files will be generated.

# Run:
```shell
mkdir execs

# Move the shaded jar file in `target` dir to `execs` dir.
cp target/otp-2.5.0-shaded.jar execs
```

> Move necessary gtfs data & osm map from [nandi repo](https://github.com/nammayatri/nandi) `assets` dir to the `execs` dir.
> 
> unzip gtfs data
> 
> remove the zips (```rm execs/*.zip```)

```shell
java -Xmx10G -jar execs/<copied jar file> --buildStreet .
java -Xmx10G -jar execs/<copied jar file> --loadStreet --save .
java -Xmx10G -jar execs/<copied jar file> --load .
``` 
