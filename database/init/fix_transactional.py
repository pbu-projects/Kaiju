import sys

file_path = '/home/jimmy/git/pbu/kaiju/core/src/test/groovy/lol/pbu/kaiju/core/controller/LocationControllerSpec.groovy'

with open(file_path, 'r') as f:
    content = f.read()

# Add Transactional import and annotation
if 'import io.micronaut.test.extensions.spock.annotation.MicronautTest' in content:
    content = content.replace('import io.micronaut.test.extensions.spock.annotation.MicronautTest', 
                              'import io.micronaut.test.extensions.spock.annotation.MicronautTest\nimport io.micronaut.transaction.annotation.Transactional')

content = content.replace('@MicronautTest\nclass LocationControllerSpec', '@MicronautTest\n@Transactional\nclass LocationControllerSpec')

with open(file_path, 'w') as f:
    f.write(content)
