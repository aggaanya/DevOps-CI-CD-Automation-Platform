INSERT INTO pipeline_versions (id, pipeline_id, version, yaml_content, commit_sha, created_by, created_at)
VALUES (gen_random_uuid(), '595057a4-b6d9-4469-bb16-63daff2739ac', 4, 
'pipeline:
  name: benchmark-sequential
  stages:
    - name: build
      jobs:
        - name: compile
          type: custom
          steps:
            - run: \"echo BUILD START && sleep 3 && echo BUILD DONE\"
    - name: test
      jobs:
        - name: unit-test
          type: test
          dependsOn: [compile]
          steps:
            - run: \"echo TEST START && sleep 5 && echo TEST DONE\"
    - name: quality
      jobs:
        - name: security-scan
          type: scan
          dependsOn: [unit-test]
          steps:
            - run: \"echo SCAN START && sleep 4 && echo SCAN DONE\"
    - name: package
      jobs:
        - name: package
          type: package
          dependsOn: [security-scan]
          steps:
            - run: \"echo PACKAGE START && sleep 2 && echo PACKAGE DONE\"',
'benchmark-sequential', 'benchmark', now());
