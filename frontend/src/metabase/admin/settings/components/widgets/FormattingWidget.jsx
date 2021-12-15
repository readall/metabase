/* eslint-disable react/prop-types */
import React from "react";
import { t } from "ttag";

import { TYPE } from "metabase/lib/types";

import ColumnSettings from "metabase/visualizations/components/ColumnSettings";

const SETTING_TYPES = [
  {
    name: t`Dates and Times`,
    type: TYPE.Temporal,
    settings: [
      "date_style",
      "date_separator",
      "date_abbreviate",
      // "time_enabled",
      "time_style",
    ],
    column: {
      semantic_type: TYPE.Temporal,
      unit: "second",
    },
  },
  {
    name: t`Numbers`,
    type: TYPE.Number,
    settings: ["number_separators"],
    column: {
      base_type: TYPE.Number,
      semantic_type: TYPE.Number,
    },
  },
  {
    name: t`Currency`,
    type: TYPE.Currency,
    settings: ["currency_style", "currency", "currency_in_header"],
    column: {
      base_type: TYPE.Number,
      semantic_type: TYPE.Currency,
    },
  },
];

class FormattingWidget extends React.Component {
  render() {
    const { setting, onChange } = this.props;
    const value = setting.value || setting.default;
    return (
      <div className="mt2">
        {SETTING_TYPES.map(({ type, name, column, settings }) => (
          <div
            key={type}
            className="border-bottom pb2 mb4 flex-full"
            style={{ minWidth: 400 }}
          >
            <h3 className="mb3">{name}</h3>
            <ColumnSettings
              value={value[type]}
              onChange={settings => onChange({ ...value, [type]: settings })}
              column={column}
              allowlist={new Set(settings)}
              forcefullyShowHiddenSettings
            />
          </div>
        ))}
      </div>
    );
  }
}

export default FormattingWidget;
