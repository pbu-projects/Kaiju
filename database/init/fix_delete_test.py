import sys

file_path = '/home/jimmy/git/pbu/kaiju/core/src/test/groovy/lol/pbu/kaiju/core/controller/LocationControllerSpec.groovy'

with open(file_path, 'r') as f:
    content = f.read()

old_delete_test = """    @Unroll
    def "DeleteLocation should remove the record"() {
        setup:
        Sql sql = new Sql(connection)
        def locationToDelete = sql.firstRow("SELECT id, name FROM locations ORDER BY name DESC LIMIT 1")
        UUID id = locationToDelete.id as UUID
        String targetName = locationToDelete.name

        when:
        locationController.deleteById(id)

        then:
        !locationRepository.findById(id).isPresent()
        sql.firstRow("SELECT count(*) as count FROM locations WHERE id = ?", [id]).count == 0
    }"""

new_delete_test = """    @Unroll
    def "DeleteLocation should remove the record"() {
        setup:
        Sql sql = new Sql(connection)
        // Create a transient location to avoid FK violations with faked data
        def tempLoc = new Location(
            null, "Test Delete Location", "123 Delete St", "Delete City", "UT", "00000", "US", createPoint()
        )
        def saved = locationController.addLocation(tempLoc)
        UUID id = saved.id()

        when:
        locationController.deleteById(id)

        then:
        !locationRepository.findById(id).isPresent()
        sql.firstRow("SELECT count(*) as count FROM locations WHERE id = ?", [id]).count == 0
    }"""

content = content.replace(old_delete_test, new_delete_test)

with open(file_path, 'w') as f:
    f.write(content)
