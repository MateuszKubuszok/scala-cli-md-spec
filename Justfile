project-version := `git describe --tags`

publish-maven-auth-options := "--user env:OSSRH_USERNAME --password env:OSSRH_PASSWORD --gpg-key $PGP_KEY_ID --gpg-option --pinentry-mode --gpg-option loopback --gpg-option --passphrase --gpg-option $PGP_PASSWORD"

show-version:
	echo {{project-version}}

publish-maven:
	scala-cli --power publish . --project-version {{project-version}} {{publish-maven-auth-options}}

publish-local:
	scala-cli --power publish local . --project-version {{project-version}}
