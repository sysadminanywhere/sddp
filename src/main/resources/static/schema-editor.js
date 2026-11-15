(() => {
  'use strict';

  const { t } = window.I18N;

  const TYPES = ['string', 'number', 'integer', 'boolean', 'object', 'array'];
  const ITEM_TYPES = ['string', 'number', 'integer', 'boolean', 'object'];

  function newField(name = 'field') {
    return { name, type: 'string', itemType: 'string', required: false, description: '', children: [] };
  }

  function parseSchema(schema) {
    if (!schema || typeof schema !== 'object' || !schema.properties || typeof schema.properties !== 'object') return [];
    const required = new Set(Array.isArray(schema.required) ? schema.required : []);
    return Object.entries(schema.properties).map(([name, property]) => {
      const field = newField(name);
      const value = property && typeof property === 'object' ? property : {};
      const type = value.type || (value.properties ? 'object' : value.items ? 'array' : 'string');
      field.type = TYPES.includes(type) ? type : 'string';
      field.required = required.has(name);
      field.description = typeof value.description === 'string' ? value.description : '';
      if (field.type === 'object') field.children = parseSchema(value);
      if (field.type === 'array') {
        const item = value.items && typeof value.items === 'object' ? value.items : {};
        field.itemType = ITEM_TYPES.includes(item.type) ? item.type : (item.properties ? 'object' : 'string');
        if (field.itemType === 'object') field.children = parseSchema(item);
      }
      return field;
    });
  }

  function buildObjectSchema(fields) {
    const properties = {};
    const required = [];
    for (const field of fields) {
      const name = String(field.name || '').trim();
      if (!name) continue;
      const property = { type: field.type };
      if (String(field.description || '').trim()) property.description = String(field.description).trim();
      if (field.type === 'object') {
        const nested = buildObjectSchema(field.children);
        if (Object.keys(nested.properties).length) property.properties = nested.properties;
        if (nested.required.length) property.required = nested.required;
      } else if (field.type === 'array') {
        if (field.itemType === 'object') {
          const nested = buildObjectSchema(field.children);
          property.items = { type: 'object' };
          if (Object.keys(nested.properties).length) property.items.properties = nested.properties;
          if (nested.required.length) property.items.required = nested.required;
        } else {
          property.items = { type: field.itemType };
        }
      }
      properties[name] = property;
      if (field.required) required.push(name);
    }
    const schema = { type: 'object', properties };
    if (required.length) schema.required = required;
    return schema;
  }

  class SchemaEditor {
    constructor(root, options = {}) {
      this.root = root;
      this.onChange = typeof options.onChange === 'function' ? options.onChange : () => {};
      this.fields = [];
      this.render();
    }

    setSchema(schema) {
      this.fields = parseSchema(schema);
      this.render();
    }

    toSchema() {
      return buildObjectSchema(this.fields);
    }

    addField() {
      this.fields.push(newField());
      this.render();
    }

    render() {
      this.root.replaceChildren();
      if (!this.fields.length) {
        const empty = document.createElement('div');
        empty.className = 'schema-empty';
        empty.textContent = t('schema.empty');
        this.root.append(empty);
      } else {
        this.fields.forEach((field, index) => this.root.append(this.renderField(field, this.fields, index, 0)));
      }
      this.onChange();
    }

    renderField(field, parent, index, depth) {
      const wrapper = document.createElement('div');
      wrapper.className = 'schema-field';
      wrapper.style.setProperty('--depth', String(depth));

      const row = document.createElement('div');
      row.className = 'schema-field-row';

      const name = document.createElement('input');
      name.className = 'schema-name';
      name.value = field.name;
      name.placeholder = 'field_name';
      name.setAttribute('aria-label', t('schema.fieldNameAria'));
      name.addEventListener('input', () => { field.name = name.value; this.onChange(); });

      const type = document.createElement('select');
      type.setAttribute('aria-label', t('schema.fieldTypeAria'));
      TYPES.forEach(value => type.append(new Option(value, value)));
      type.value = field.type;
      type.addEventListener('change', () => {
        field.type = type.value;
        this.render();
      });

      const required = document.createElement('label');
      required.className = 'inline schema-required';
      const checkbox = document.createElement('input');
      checkbox.type = 'checkbox';
      checkbox.checked = field.required;
      checkbox.addEventListener('change', () => { field.required = checkbox.checked; this.onChange(); });
      required.append(checkbox, document.createTextNode(t('schema.required')));

      const description = document.createElement('input');
      description.className = 'schema-description';
      description.value = field.description;
      description.placeholder = t('schema.descriptionPlaceholder');
      description.setAttribute('aria-label', t('schema.descriptionAria'));
      description.addEventListener('input', () => { field.description = description.value; this.onChange(); });

      row.append(name, type);

      if (field.type === 'array') {
        const itemType = document.createElement('select');
        itemType.setAttribute('aria-label', t('schema.itemTypeAria'));
        ITEM_TYPES.forEach(value => itemType.append(new Option(`items: ${value}`, value)));
        itemType.value = field.itemType;
        itemType.addEventListener('change', () => {
          field.itemType = itemType.value;
          this.render();
        });
        row.append(itemType);
      }

      row.append(required, description);

      if (field.type === 'object' || (field.type === 'array' && field.itemType === 'object')) {
        const add = document.createElement('button');
        add.type = 'button';
        add.className = 'secondary schema-small';
        add.textContent = t('schema.addField');
        add.addEventListener('click', () => { field.children.push(newField()); this.render(); });
        row.append(add);
      }

      const remove = document.createElement('button');
      remove.type = 'button';
      remove.className = 'danger schema-small';
      remove.textContent = t('schema.delete');
      remove.addEventListener('click', () => { parent.splice(index, 1); this.render(); });
      row.append(remove);

      wrapper.append(row);

      if (field.type === 'object' || (field.type === 'array' && field.itemType === 'object')) {
        const children = document.createElement('div');
        children.className = 'schema-children';
        if (!field.children.length) {
          const hint = document.createElement('div');
          hint.className = 'schema-empty';
          hint.textContent = field.type === 'array' ? t('schema.arrayNoObjectFields') : t('schema.objectNoFields');
          children.append(hint);
        } else {
          field.children.forEach((child, childIndex) => children.append(this.renderField(child, field.children, childIndex, depth + 1)));
        }
        wrapper.append(children);
      }

      return wrapper;
    }
  }

  window.SchemaEditor = SchemaEditor;
})();
