import json
from uuid import uuid4

def create_md_file(md_dict, attach_list, sub_task_list):
    main_task_template = '''# Summary
{summary}
## Description
{description}
## Task Info
* Status: {status}
* Updated: {updated}
* Issuetype: {issuetype}
## Labels
{labels}
## Market Affected Field Name
{marketAffectedFieldName}
## Acceptance Criteria Field Name
{acceptanceCriteriaFieldName}
---
'''
    attachment_template = '''{file_name}
* ID: {file_id}
* Created: {created}
* File Size: {size}
* Download URL: {url}
---
'''
    sub_tasks_template = '''{summary}
* Key: {key}
* URL: {url}
---
'''
    md_content = main_task_template.format(**md_dict)
    if attach_list:
        md_content += '## Attachment\n'
        for attach in attach_list:
            md_content += attachment_template.format(**attach)
    if sub_task_list:
        md_content += '## Sub Tasks\n'
        for sub_task in sub_task_list:
            md_content += sub_tasks_template.format(**sub_task)
    return md_content

def create_meta_file(
        level=0, 
        title=None, 
        file_name=None, 
        file_record_id=None, 
        file_original_name=None,
        file_original_path=None,
        source=None,
        value_stream=None,
        categories=None,
        parent_file_name=None, 
        attachment_type="file",
        owner=None
    ):
    meta_content = {
        'level': level,
        'title': title,
        'file_name': file_name,
        'file_record_id': file_record_id,
        'file_original_name': file_original_name,
        'file_original_path': file_original_path,
        'source': source,
        'value_stream': value_stream,
        'categories': categories,
        'parent_file_name': parent_file_name,
        'attachment_type': attachment_type,
        'owner': owner,
    }
    return json.dumps(meta_content, ensure_ascii=False)

def proc_task(json_result, is_main_task=True):
    record_id = uuid4()
    md_file_name = record_id + '.md'
    if is_main_task:
        main_task = json_result['issues'][0]
    else:
        main_task = json_result
    fields = main_task['fields']
    file_name = main_task['key']
    

    # Create Main Task Info
    labels_str = ', '.join(fields['labels'])
    desc = ' '.join(line for line in fields['description'].splitlines() if line.strip())
    if fields['customfield_27708']:
        acfn = '\n'.join(line for line in fields['customfield_27708'].splitlines() if line.strip())
    else:
        acfn = ''
    if fields['customfield_26615']:
        mafn = fields['customfield_26615'][0]['value']
    else:
        mafn = ''
    md_dict = {
        'summary': fields['summary'],
        'description': desc,
        'status': fields['status']['name'],
        'updated': fields['updated'],
        'issuetype': fields['issuetype']['name'],
        'labels': labels_str,
        'marketAffectedFieldName': mafn,
        'acceptanceCriteriaFieldName': acfn,  
    }

    # Create Attachment List
    if fields['attachment']:
        attach_list = [{'file_id':info['id'], 'file_name':info['filename'], 'created':info['created'], 'size':info['size'], 'url':info['content']} for info in fields['attachment']]
    else:
        attach_list = []

    # Create Sub Task List
    sub_task_list = []
    if len(json_result['issues']) > 1:
        for sub_task in json_result['issues'][1:]:
            sub_task_list.append(
                {
                    'summary': sub_task['fields']['summary'],
                    'key': sub_task['key'],
                    'url': sub_task['self'],
                }
            )

    # create markdown file
    main_md_content = create_md_file(md_dict, attach_list, sub_task_list)
        
    # Create Main Task Meta
    main_meta_content = create_meta_file(
        level=0,
        title=md_dict['summary'],
        file_name=md_file_name,
        file_record_id=record_id,
        file_original_name=md_dict['summary'],
        file_original_path=main_task['self'] + '/' + md_file_name,
        source="jira-iwpb", # for test,
        value_stream="",
        categories="",
        parent_file_name=None,
        attachment_type="file",
        owner="",
    )
    return main_md_content, main_meta_content, file_name
    

def proc(json_result):
    main_md_content, main_meta_content, md_file_name = proc_task(json_result)
    # TODO: Long Content for AI Assistant Directly Access
    # TODO: Complete file push task